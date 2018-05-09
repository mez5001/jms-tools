package edu.psu.activemq;

import java.io.PrintWriter;
import java.io.StringWriter;

import javax.jms.Connection;
import javax.jms.Destination;
import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.MessageProducer;
import javax.jms.Queue;
import javax.jms.Session;
import javax.jms.TextMessage;

import org.apache.activemq.ActiveMQConnection;
import org.apache.activemq.ActiveMQMessageConsumer;
import org.apache.activemq.ActiveMQMessageProducer;
import org.apache.activemq.RedeliveryPolicy;
import org.apache.activemq.ScheduledMessage;
import org.apache.activemq.command.ActiveMQMessage;
import org.apache.activemq.command.ActiveMQQueue;
import org.slf4j.MDC;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.AnnotationIntrospector;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.introspect.AnnotationIntrospectorPair;
import com.fasterxml.jackson.databind.introspect.JacksonAnnotationIntrospector;
import com.fasterxml.jackson.module.jaxb.JaxbAnnotationIntrospector;

import edu.psu.activemq.data.ErrorMessage;
import edu.psu.activemq.exception.UnableToProcessMessageException;
import lombok.AccessLevel;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Data
public abstract class MessageProcessor {

  private static final String DELIVERY_COUNT_PROP_NAME = "swe-delivery-count";
  private static final String UNIQUE_ID_MDC_KEY = "uniqueId";

  @Getter(value = AccessLevel.NONE)
  @Setter(value = AccessLevel.NONE)
  boolean process = true;

  @Getter(value = AccessLevel.NONE)
  @Setter(value = AccessLevel.NONE)
  boolean stopped = false;

  String brokerUrl = null;
  String transportName = null;
  String errorTransportName = null;
  TransportType errorTransportType = null;
  String username;
  String password;
  int requestRetryThreshold;
  boolean errorMessageConvert = false;

  ActiveMQMessageConsumer consumer = null;
  ActiveMQMessageProducer producer = null;
  MessageProducer errorProducer = null;
  Session errorSession;

  ObjectMapper objectMapper = new ObjectMapper();

  protected abstract void handleMessage(Message message) throws UnableToProcessMessageException;

  public MessageProcessor() {
    AnnotationIntrospector jaxbIntrospector = new JaxbAnnotationIntrospector(objectMapper.getTypeFactory());
    AnnotationIntrospector jacksonIntrospector = new JacksonAnnotationIntrospector();
    AnnotationIntrospector pair = new AnnotationIntrospectorPair(jacksonIntrospector, jaxbIntrospector);
    objectMapper.setAnnotationIntrospector(pair);
  }

  public void terminate() {
    process = false;
  }

  void initialize() {
    log.info("Initializing message processor...");

    Thread t = new Thread(new Runnable() {
      public void run() {

        ActiveMQConnection connection = null;
        Connection errorConnection = null;

        try {
          try {
            connection = (ActiveMQConnection) MessageHandler.buildActivemqConnection(brokerUrl, username, password);

            RedeliveryPolicy rd = new RedeliveryPolicy();
            rd.setMaximumRedeliveries(2);
            connection.getRedeliveryPolicyMap()
                      .put(new ActiveMQQueue(transportName), rd);

            connection.start();
            Session session = connection.createSession(false, Session.CLIENT_ACKNOWLEDGE);

            Queue destination = session.createQueue(transportName);
            consumer = (ActiveMQMessageConsumer) session.createConsumer(destination);
            // Used for re-queuing messages at the users requests
            producer = (ActiveMQMessageProducer) session.createProducer(destination);

          } catch (JMSException e) {
            log.info("Error creating message consumer", e);
            stopped = true;
            throw new RuntimeException("Failed to initialize processing queue");
          }

          if (errorTransportName != null) {
            try {
              errorConnection = MessageHandler.buildActivemqConnection(brokerUrl, username, password);
              errorConnection.start();
              errorSession = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);

              Destination errorDestination = null;
              if (TransportType.TOPIC.equals(errorTransportType)) {
                errorDestination = errorSession.createTopic(errorTransportName);
              } else {
                errorDestination = errorSession.createQueue(errorTransportName);
              }

              errorProducer = errorSession.createProducer(errorDestination);
            } catch (JMSException e) {
              log.error("Error creating error producer", e);
              stopped = true;
              throw new RuntimeException("Failed to initialize the error endpoint");
            }
          }

          try {
            Message message = null;
            while (process) {
              message = consumer.receive();
              try {

                // set unique id for logging
                try {
                  MDC.put(UNIQUE_ID_MDC_KEY, message.getJMSMessageID());
                } catch (IllegalArgumentException | JMSException e1) {
                  log.error("Error setting MDC unique id", e1);
                }

                handleMessage(message);
                consumer.acknowledge();
              } catch (UnableToProcessMessageException upme) {
                if (UnableToProcessMessageException.HandleAction.RETRY.equals(upme.getHandleAction())) {
                  ActiveMQMessage msg = (ActiveMQMessage) message;
                  msg.setReadOnlyProperties(false);
                  msg.setLongProperty(ScheduledMessage.AMQ_SCHEDULED_DELAY, upme.getRetryWait());

                  log.debug("Getting retry count");
                  int retryCount = 0;
                  String retryCountString = message.getStringProperty(DELIVERY_COUNT_PROP_NAME);
                  if (retryCountString != null) {
                    retryCount = Integer.parseInt(retryCountString);
                  }
                  log.debug("Current count: {}, Threshold: {}", String.valueOf(retryCount), String.valueOf(requestRetryThreshold));
                  if (retryCount >= requestRetryThreshold) {
                    log.info("Retry count greater than threshold, process failure message");
                    processFailureMessage(message, upme);
                  } else {
                    log.info("Retry count less than threshold, increment count and requeue");
                    msg.setIntProperty(DELIVERY_COUNT_PROP_NAME, ++retryCount);
                    // send message back to queue with greater retry count
                    producer.send(msg);
                  }
                } else if (UnableToProcessMessageException.HandleAction.DROP.equals(upme.getHandleAction())) {
                  log.info("Dropping message {}", message.getJMSMessageID());
                } else {
                  processFailureMessage(message, upme);
                }

                consumer.acknowledge();
              } catch (Exception e) {
                processFailureMessage(message, e);
                consumer.acknowledge();
              } finally {
                // remove unique id for logging
                try {
                  MDC.remove(UNIQUE_ID_MDC_KEY);
                } catch (IllegalArgumentException e1) {
                  log.error("Error remvoing MDC unique id");
                }
              }
            }
            log.info("Stopping processor");
          } catch (Exception e) {
            stopped = true;
            log.error("Processor exception processing message.", e);

            try {
              consumer.rollback();
            } catch (JMSException e1) {
            }
          }
        } finally {
          try {
            consumer.close();
          } catch (Exception e) {
          }
          //close connection
          try {
            if (connection != null) {
              log.info("Closing connection");
              connection.close();
            }
          } catch (Exception e) {
            log.warn("Error closing connection", e);
          }
          //close error connection
          try {
            if (errorConnection != null) {
              log.info("Closing error connection");
              errorConnection.close();
            }
          } catch (Exception e) {
            log.warn("Error closing error connection", e);
          }
        }
      }
    });
    t.start();
  }

  private void processFailureMessage(Message message, Exception e) {
    log.warn("Error processing message", e);
    if (errorProducer != null) {
      log.info("Sending to error queue");
      ActiveMQMessage msg = (ActiveMQMessage) message;
      msg.setReadOnlyProperties(false);
      try {
        // convert to an error message object
        if (errorMessageConvert) {
          ErrorMessage em = new ErrorMessage();

          if (e instanceof UnableToProcessMessageException) {
            em.setShortDescription(((UnableToProcessMessageException) e).getShortDescription());
            em.setSourceSystem(((UnableToProcessMessageException) e).getSourceSystem());
          } else {
            // Try to grab something for the short Message
            String shortMessage = e.getMessage();
            if (shortMessage != null) {
              int shortMessageLength = shortMessage.length() > 256 ? 256 : shortMessage.length();
              em.setShortDescription(e.getMessage()
                                      .substring(0, shortMessageLength));
            } else {
              em.setShortDescription("Error: " + e.getClass()
                                                  .getName());
            }
          }

          em.setDescription(e.getMessage());
          em.setStack(getStackTrace(e));
          try {
            TextMessage tm = errorSession.createTextMessage(objectMapper.writeValueAsString(em));
            errorProducer.send(tm);
            consumer.acknowledge();
          } catch (JsonProcessingException e1) {
            log.error("Unable to send message on error queue: " + em.toString());
          }
        }
        // send original message with error headers
        else {
          msg.setStringProperty("error", e.getMessage());
          msg.setStringProperty("errorStackTrace", getStackTrace(e));
          errorProducer.send(msg);
        }
      } catch (JMSException e1) {
        if (e instanceof UnableToProcessMessageException) {
          UnableToProcessMessageException up = (UnableToProcessMessageException) e;
          log.error("Unable to send message on error queue: {} {} {}", up.getShortDescription(), up.getSourceSystem(), up.getMessage());
        }
        log.error("Unable to send message on error queue: " + e.getMessage());
      }
    } else {
      try {
        consumer.rollback();
      } catch (JMSException e1) {
        // TODO Auto-generated catch block
        e1.printStackTrace();
      }
    }
  }

  private String getStackTrace(Exception e) {
    StringWriter sw = new StringWriter();
    PrintWriter pw = new PrintWriter(sw);
    e.printStackTrace(pw);
    return sw.toString();
  }

  public boolean isStopped() {
    return stopped;
  }
}
