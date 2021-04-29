package newrelic.kafkaplayground.consumer;

import com.newrelic.api.agent.NewRelic;
import com.newrelic.api.agent.Trace;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalTime;
import java.util.List;
import java.util.Properties;
import java.util.Random;

public class ApplicationMessagesLoop implements Runnable {

    final static Logger logger = LoggerFactory.getLogger(ApplicationMessagesLoop.class);
    
    private final Random rand;
    
    private final Properties consumerProperites;
    
    private final LocalTime[] lagRegressions;
    private final LocalTime[] consumerRegressions;
    private final int lagRegressionMinutes = 15;
    private final int lagSleep = 100;
    private final int consumerRegressionMinutes = 30;

    private final KafkaConsumer<String, String> consumer;
    private final List<String> topics;

    @Trace(dispatcher = true)
    private void processMessage(ConsumerRecord<String, String> record) throws InterruptedException{
        LocalTime n = LocalTime.now();
        Duration diff1 = Duration.between(this.lagRegressions[0], n);
        Duration diff2 = Duration.between(this.lagRegressions[1], n);
        
        if ((diff1.toMinutes() > 0 && diff1.toMinutes() < this.lagRegressionMinutes) || (diff2.toMinutes() > 0 && diff2.toMinutes() < this.lagRegressionMinutes)) {
            Thread.sleep(rand.nextInt(this.lagSleep));
            NewRelic.getAgent().getTracedMethod().addCustomAttribute("somethingIsBroken", "true");
        }
        Iterable<Header> headers = record.headers().headers("newrelic");
        for (Header header : headers) {
            String nrpayload = new String(header.value(), StandardCharsets.UTF_8);
            NewRelic.getAgent().getTransaction().acceptDistributedTracePayload(nrpayload);
        }

        // annotate this span with metadata from the record
        // https://github.com/open-telemetry/opentelemetry-specification/blob/main/specification/trace/semantic_conventions/messaging.md
        NewRelic.getAgent().getTracedMethod().addCustomAttribute("messaging.system", "kafka");
        NewRelic.getAgent().getTracedMethod().addCustomAttribute("messaging.kafka.consumer_group", this.consumer.groupMetadata().groupId());
        NewRelic.getAgent().getTracedMethod().addCustomAttribute("messaging.kafka.client_id", this.consumerProperites.getProperty("client.id"));
        NewRelic.getAgent().getTracedMethod().addCustomAttribute("messaging.destination", record.topic());
        NewRelic.getAgent().getTracedMethod().addCustomAttribute("messaging.destination_kind", "topic");
        NewRelic.getAgent().getTracedMethod().addCustomAttribute("messaging.operation", "process");
        NewRelic.getAgent().getTracedMethod().addCustomAttribute("messaging.kafka.partition", record.partition());
        NewRelic.getAgent().getTracedMethod().addCustomAttribute("messaging.kafka.offset", record.offset());
        NewRelic.getAgent().getTracedMethod().addCustomAttribute("messaging.kafka.timestamp", record.timestamp());

        // only log if the trace is sampled to demonstrate logs-in-context
        if (NewRelic.getAgent().getTraceMetadata().isSampled()) {
            logger.info("[Consumer clientId={} memberId={}, groupId={}] consumed message: {}", this.consumerProperites.getProperty("client.id"), this.consumer.groupMetadata().memberId(), this.consumer.groupMetadata().groupId(), record.value());
        }

    }

    public ApplicationMessagesLoop(List<String> topics, Properties consumerProperites) {
        this.topics = topics;
        this.consumerProperites = consumerProperites;
        this.consumer = new KafkaConsumer<>(this.consumerProperites);
        this.rand = new Random();
        this.lagRegressions = new LocalTime[2];
        this.lagRegressions[0] = LocalTime.of(rand.nextInt(24), rand.nextInt(60));
        this.lagRegressions[1] = LocalTime.of(rand.nextInt(24), rand.nextInt(60));
        this.consumerRegressions = new LocalTime[2];
        this.consumerRegressions[0] = LocalTime.of(rand.nextInt(24), this.rand.nextInt(60));
        this.consumerRegressions[1] = LocalTime.of(rand.nextInt(24), this.rand.nextInt(60));
        logger.info("Created clientId={} lag regressions: {}, {}", this.consumerProperites.getProperty("client.id"), this.lagRegressions[0].toString(), this.lagRegressions[1].toString());
        logger.info("Created clientId={} consumer regressions: {}, {}", this.consumerProperites.getProperty("client.id"), this.consumerRegressions[0].toString(), this.consumerRegressions[1].toString());
    }

    @Override
    public void run() {
        try {
            //NotifyOnRebalance nor = new NotifyOnRebalance();
            //consumer.subscribe(topics, nor);
        

            while (true) {
                LocalTime n = LocalTime.now();
                Duration diff1 = Duration.between(this.consumerRegressions[0], n);
                Duration diff2 = Duration.between(this.consumerRegressions[1], n);
                if ((diff1.toMinutes() > 0 && diff1.toMinutes() < this.consumerRegressionMinutes) || (diff2.toMinutes() > 0 && diff2.toMinutes() < this.consumerRegressionMinutes)) {
                    if (!this.consumer.subscription().isEmpty()) {
                        logger.info("unsubscribing memberId={}",  this.consumer.groupMetadata().memberId());
                        this.consumer.unsubscribe();
                    }
                } else {
                     if (this.consumer.subscription().isEmpty()) {
                        logger.info("subscribing memberId={}",  this.consumer.groupMetadata().memberId());
                        this.consumer.subscribe(this.topics);
                    }
                    ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(Long.MAX_VALUE));
                    for (ConsumerRecord<String, String> record : records) {
                        processMessage(record);
                    }
                }
            }
        } catch (WakeupException e) {
            // ignore for shutdown
        } catch (InterruptedException e) {
            // ignore for shutdown
        } finally {
            consumer.close();
        }
    }

    public void shutdown() {
        consumer.wakeup();
    }
}