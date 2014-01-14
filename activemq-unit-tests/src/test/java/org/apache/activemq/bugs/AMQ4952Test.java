
package org.apache.activemq.bugs;

import junit.framework.TestCase;
import org.apache.activemq.ActiveMQConnectionFactory;
import org.apache.activemq.broker.*;
import org.apache.activemq.broker.region.policy.PolicyEntry;
import org.apache.activemq.broker.region.policy.PolicyMap;
import org.apache.activemq.command.*;
import org.apache.activemq.network.ConditionalNetworkBridgeFilterFactory;
import org.apache.activemq.network.NetworkConnector;
import org.apache.activemq.store.jdbc.JDBCPersistenceAdapter;
import org.apache.activemq.util.IntrospectionSupport;
import org.apache.activemq.util.Wait;
import org.apache.derby.jdbc.EmbeddedDataSource;
import org.junit.After;
import org.junit.Before;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jms.*;
import javax.jms.Connection;
import javax.sql.DataSource;
import java.net.URI;
import java.sql.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.*;

/**
 * Test creates a broker network with two brokers -
 * producerBroker (with a message producer attached) and consumerBroker (with consumer attached)
 * <p/>
 * Simulates network duplicate message by stopping and restarting the consumerBroker after message (with message ID ending in
 * 120) is persisted to consumerBrokerstore BUT BEFORE ack sent to the producerBroker over the network connection.
 * When the network connection is reestablished the producerBroker resends
 * message (with messageID ending in 120).
 * <p/>
 * Expectation:
 * <p/>
 * With the following policy entries set,  would  expect the duplicate message to be read from the store
 * and dispatched to the consumer - where the duplicate could be detected by consumer.
 * <p/>
 * PolicyEntry policy = new PolicyEntry();
 * policy.setQueue(">");
 * policy.setEnableAudit(false);
 * policy.setUseCache(false);
 * policy.setExpireMessagesPeriod(0);
 * <p/>
 * <p/>
 * Note 1: Network needs to use replaywhenNoConsumers so enabling the networkAudit to avoid this scenario is not feasible.
 * <p/>
 * NOTE 2: Added a custom plugin to the consumerBroker so that the consumerBroker shutdown will occur after a message has been
 * persisted to consumerBroker store but before an ACK is sent back to ProducerBroker. This is just a hack to ensure producerBroker will resend
 * the message after shutdown.
 */

@RunWith(value = Parameterized.class)
public class AMQ4952Test extends TestCase {

    private static final Logger LOG = LoggerFactory.getLogger(AMQ4952Test.class);

    protected static final int MESSAGE_COUNT = 1;

    protected BrokerService consumerBroker;
    protected BrokerService producerBroker;

    protected ActiveMQQueue QUEUE_NAME = new ActiveMQQueue("duptest.store");

    private final CountDownLatch stopConsumerBroker = new CountDownLatch(1);
    private final CountDownLatch consumerBrokerRestarted = new CountDownLatch(1);
    private final CountDownLatch consumerRestartedAndMessageForwarded = new CountDownLatch(1);

    private EmbeddedDataSource localDataSource;

    @Parameterized.Parameter(0)
    public boolean enableCursorAudit;

    @Parameterized.Parameters(name="enableAudit={0}")
    public static Iterable<Object[]> getTestParameters() {
        return Arrays.asList(new Object[][]{{Boolean.TRUE},{Boolean.FALSE}});
    }

    @Test
    public void testConsumerBrokerRestart() throws Exception {

        Callable consumeMessageTask = new Callable() {
            @Override
            public Object call() throws Exception {

                int receivedMessageCount = 0;

                ActiveMQConnectionFactory consumerFactory = new ActiveMQConnectionFactory("failover:(tcp://localhost:2006)?randomize=false&backup=false");
                Connection consumerConnection = consumerFactory.createConnection();

                try {

                    consumerConnection.setClientID("consumer");
                    consumerConnection.start();

                    Session consumerSession = consumerConnection.createSession(false, Session.CLIENT_ACKNOWLEDGE);
                    MessageConsumer messageConsumer = consumerSession.createConsumer(QUEUE_NAME);


                    while (true) {
                        TextMessage textMsg = (TextMessage) messageConsumer.receive(5000);

                        if (textMsg == null) {
                            return receivedMessageCount;
                        }

                        receivedMessageCount++;
                        LOG.info("*** receivedMessageCount {} message has MessageID {} ", receivedMessageCount, textMsg.getJMSMessageID());

                        // on first delivery ensure the message is pending an ack when it is resent from the producer broker
                        if (textMsg.getJMSMessageID().endsWith("1") && receivedMessageCount == 1) {
                            LOG.info("Waiting for restart...");
                            consumerRestartedAndMessageForwarded.await(90, TimeUnit.SECONDS);
                        }

                        textMsg.acknowledge();

                    }
                } finally {
                    consumerConnection.close();
                }
            }
        };

        Runnable consumerBrokerResetTask = new Runnable() {
            public void run() {

                try {
                    // wait for signal
                    stopConsumerBroker.await();


                    LOG.info("********* STOPPING CONSUMER BROKER");

                    consumerBroker.stop();
                    consumerBroker.waitUntilStopped();


                    LOG.info("***** STARTING CONSUMER BROKER");
                    // do not delete  messages on startup
                    consumerBroker = createConsumerBroker(false);

                    LOG.info("***** CONSUMER BROKER STARTED!!");
                    consumerBrokerRestarted.countDown();

                    assertTrue("message forwarded on time", Wait.waitFor(new Wait.Condition() {
                        @Override
                        public boolean isSatisified() throws Exception {
                            LOG.info("ProducerBroker totalMessageCount: " + producerBroker.getAdminView().getTotalMessageCount());
                            return producerBroker.getAdminView().getTotalMessageCount() == 0;
                        }
                    }));
                    consumerRestartedAndMessageForwarded.countDown();


                } catch (Exception e) {
                    LOG.error("Exception when stopping/starting the consumerBroker ", e);
                }


            }
        };


        ExecutorService executor = Executors.newFixedThreadPool(2);

        //start consumerBroker start/stop task
        executor.execute(consumerBrokerResetTask);

        //start consuming messages
        Future<Integer> numberOfConsumedMessage = executor.submit(consumeMessageTask);


        produceMessages();

        //Wait for consumer to finish
        int totalMessagesConsumed = numberOfConsumedMessage.get();

        StringBuffer contents = new StringBuffer();
        boolean messageInStore = isMessageInJDBCStore(localDataSource, contents);
        LOG.debug("****number of messages received " + totalMessagesConsumed);

        assertEquals("number of messages received", 2, totalMessagesConsumed);
        assertEquals("messages left in store", true, messageInStore);
        assertTrue("message is in dlq: " + contents.toString(), contents.toString().contains("DLQ"));

    }

    private void produceMessages() throws JMSException {

        ActiveMQConnectionFactory producerFactory = new ActiveMQConnectionFactory("failover:(tcp://localhost:2003)?randomize=false&backup=false");
        Connection producerConnection = producerFactory.createConnection();

        try {
            producerConnection.setClientID("producer");
            producerConnection.start();

            Session producerSession = producerConnection.createSession(false, Session.AUTO_ACKNOWLEDGE);

            final MessageProducer remoteProducer = producerSession.createProducer(QUEUE_NAME);

            int i = 0;
            while (MESSAGE_COUNT > i) {
                String payload = "test msg " + i;
                TextMessage msg = producerSession.createTextMessage(payload);
                remoteProducer.send(msg);
                i++;
            }

        } finally {
            producerConnection.close();
        }
    }

    @Override
    @Before
    public void setUp() throws Exception {
        super.setUp();
        doSetUp();
    }

    @Override
    @After
    public void tearDown() throws Exception {
        doTearDown();
        super.tearDown();
    }

    protected void doTearDown() throws Exception {

        try {
            producerBroker.stop();
        } catch (Exception ex) {
        }
        try {
            consumerBroker.stop();
        } catch (Exception ex) {
        }
    }

    protected void doSetUp() throws Exception {
        producerBroker = createProducerBroker();
        consumerBroker = createConsumerBroker(true);
    }


    /**
     * Producer broker
     * listens on  localhost:2003
     * networks to consumerBroker - localhost:2006
     *
     * @return
     * @throws Exception
     */

    protected BrokerService createProducerBroker() throws Exception {


        String networkToPorts[] = new String[]{"2006"};
        HashMap<String, String> networkProps = new HashMap<String, String>();

        networkProps.put("networkTTL", "10");
        networkProps.put("conduitSubscriptions", "true");
        networkProps.put("decreaseNetworkConsumerPriority", "true");
        networkProps.put("dynamicOnly", "true");

        BrokerService broker = new BrokerService();
        broker.getManagementContext().setCreateConnector(false);
        broker.setDeleteAllMessagesOnStartup(true);
        broker.setBrokerName("BP");
        broker.setAdvisorySupport(false);

        // lazy init listener on broker start
        TransportConnector transportConnector = new TransportConnector();
        transportConnector.setUri(new URI("tcp://localhost:2003"));
        List<TransportConnector> transportConnectors = new ArrayList<TransportConnector>();
        transportConnectors.add(transportConnector);
        broker.setTransportConnectors(transportConnectors);


        //network to consumerBroker

        if (networkToPorts != null && networkToPorts.length > 0) {
            StringBuilder builder = new StringBuilder("static:(failover:(tcp://localhost:2006)?maxReconnectAttempts=0)?useExponentialBackOff=false");
            NetworkConnector nc = broker.addNetworkConnector(builder.toString());
            if (networkProps != null) {
                IntrospectionSupport.setProperties(nc, networkProps);
            }
            nc.setStaticallyIncludedDestinations(Arrays.<ActiveMQDestination>asList(new ActiveMQQueue[]{QUEUE_NAME}));
        }

        //Persistence adapter

        JDBCPersistenceAdapter jdbc = new JDBCPersistenceAdapter();
        EmbeddedDataSource remoteDataSource = new EmbeddedDataSource();
        remoteDataSource.setDatabaseName("derbyDBRemoteBroker");
        remoteDataSource.setCreateDatabase("create");
        jdbc.setDataSource(remoteDataSource);
        broker.setPersistenceAdapter(jdbc);

        //set Policy entries
        PolicyEntry policy = new PolicyEntry();

        policy.setQueue(">");
        policy.setEnableAudit(false);
        policy.setUseCache(false);
        policy.setExpireMessagesPeriod(0);

        // set replay with no consumers
        ConditionalNetworkBridgeFilterFactory conditionalNetworkBridgeFilterFactory =
                new ConditionalNetworkBridgeFilterFactory();
        conditionalNetworkBridgeFilterFactory.setReplayWhenNoConsumers(true);
        policy.setNetworkBridgeFilterFactory(conditionalNetworkBridgeFilterFactory);

        PolicyMap pMap = new PolicyMap();
        pMap.setDefaultEntry(policy);
        broker.setDestinationPolicy(pMap);

        broker.start();
        broker.waitUntilStarted();

        return broker;
    }


    /**
     * consumerBroker
     * - listens on localhost:2006
     *
     * @param deleteMessages - drop messages when broker instance is created
     * @return
     * @throws Exception
     */

    protected BrokerService createConsumerBroker(boolean deleteMessages) throws Exception {

        String scheme = "tcp";
        String listenPort = "2006";

        BrokerService broker = new BrokerService();
        broker.getManagementContext().setCreateConnector(false);
        broker.setDeleteAllMessagesOnStartup(deleteMessages);
        broker.setBrokerName("BC");
        // lazy init listener on broker start
        TransportConnector transportConnector = new TransportConnector();
        transportConnector.setUri(new URI(scheme + "://localhost:" + listenPort));
        List<TransportConnector> transportConnectors = new ArrayList<TransportConnector>();
        transportConnectors.add(transportConnector);
        broker.setTransportConnectors(transportConnectors);

        //policy entries

        PolicyEntry policy = new PolicyEntry();

        policy.setQueue(">");
        policy.setEnableAudit(enableCursorAudit);
        policy.setExpireMessagesPeriod(0);

        // set replay with no consumers
        ConditionalNetworkBridgeFilterFactory conditionalNetworkBridgeFilterFactory =
                new ConditionalNetworkBridgeFilterFactory();
        conditionalNetworkBridgeFilterFactory.setReplayWhenNoConsumers(true);
        policy.setNetworkBridgeFilterFactory(conditionalNetworkBridgeFilterFactory);

        PolicyMap pMap = new PolicyMap();

        pMap.setDefaultEntry(policy);
        broker.setDestinationPolicy(pMap);


        // Persistence adapter
        JDBCPersistenceAdapter localJDBCPersistentAdapter = new JDBCPersistenceAdapter();
        EmbeddedDataSource localDataSource = new EmbeddedDataSource();
        localDataSource.setDatabaseName("derbyDBLocalBroker");
        localDataSource.setCreateDatabase("create");
        localJDBCPersistentAdapter.setDataSource(localDataSource);
        broker.setPersistenceAdapter(localJDBCPersistentAdapter);

        if (deleteMessages) {
            // no plugin on restart
            broker.setPlugins(new BrokerPlugin[]{new MyTestPlugin()});
        }

        this.localDataSource = localDataSource;

        broker.start();
        broker.waitUntilStarted();

        return broker;
    }


    /**
     * Query JDBC Store to see if messages are left
     *
     * @param dataSource
     * @return
     * @throws SQLException
     */

    private boolean isMessageInJDBCStore(DataSource dataSource, StringBuffer stringBuffer)
            throws SQLException {

        boolean tableHasData = false;
        String query = "select * from ACTIVEMQ_MSGS";

        java.sql.Connection conn = dataSource.getConnection();
        PreparedStatement s = conn.prepareStatement(query);

        ResultSet set = null;



        try {
            StringBuffer headers = new StringBuffer();
            set = s.executeQuery();
            ResultSetMetaData metaData = set.getMetaData();
            for (int i = 1; i <= metaData.getColumnCount(); i++) {

                if (i == 1) {
                    headers.append("||");
                }
                headers.append(metaData.getColumnName(i) + "||");
            }
            LOG.error(headers.toString());


            while (set.next()) {
                tableHasData = true;

                for (int i = 1; i <= metaData.getColumnCount(); i++) {
                    if (i == 1) {
                        stringBuffer.append("|");
                    }
                    stringBuffer.append(set.getString(i) + "|");
                }
                LOG.error(stringBuffer.toString());
            }
        } finally {
            try {
                set.close();
            } catch (Throwable ignore) {
            }
            try {
                s.close();
            } catch (Throwable ignore) {
            }

            conn.close();
        }

        return tableHasData;
    }


    /**
     * plugin used to ensure consumerbroker is restared before the network message from producerBroker is acked
     */
    class MyTestPlugin implements BrokerPlugin {

        public Broker installPlugin(Broker broker) throws Exception {
            return new MyTestBroker(broker);
        }

    }

    class MyTestBroker extends BrokerFilter {

        public MyTestBroker(Broker next) {
            super(next);
        }

        public void send(ProducerBrokerExchange producerExchange, org.apache.activemq.command.Message messageSend) throws Exception {

            super.send(producerExchange, messageSend);
            LOG.error("Stopping broker on send:  " +messageSend.getMessageId().getProducerSequenceId());
            stopConsumerBroker.countDown();
            producerExchange.getConnectionContext().setDontSendReponse(true);
        }
    }
}