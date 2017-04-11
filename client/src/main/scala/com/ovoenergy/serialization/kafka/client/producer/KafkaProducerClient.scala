package com.ovoenergy.serialization.kafka.client.producer

import akka.actor.{Actor, ActorRef, ActorRefFactory, Cancellable, Props}
import com.ovoenergy.serialization.kafka.client.producer.KafkaProducerClient.Protocol
import com.ovoenergy.serialization.kafka.client.producer.Producers._
import com.ovoenergy.serialization.kafka.client.util.ConfigUtils._
import com.typesafe.config.Config
import org.apache.kafka.clients.producer.{Producer, ProducerRecord, KafkaProducer => jKafkaProducer}

import scala.collection.mutable
import scala.concurrent.Future
import scala.util.control.NonFatal

/**
  * A lightweight, non-blocking wrapper around the Apache Kafka Producer class.
  */
private[producer] final class KafkaProducerClient[K, V](config: ProducerConfig, producerFactory: () => Producer[K, V]) extends Actor {

  private implicit val ec = context.system.dispatchers.lookup("kafka.producer.dispatcher")

  private implicit val log = context.system.log

  private val eventQueue = mutable.Queue.empty[Event[K, V]]

  private var producer: Producer[K, V] = _

  private var sendEventsJob: Cancellable = _

  override def preRestart(reason: Throwable, message: Option[Any]): Unit = {
    log.error(reason, s"Restarting kafka producer: [$message]")
    eventQueue.dequeueAll(_ => true) foreach (event => self ! event)
    super.preRestart(reason, message)
  }

  override def preStart(): Unit = {
    super.preStart()
    log.debug(s"Starting kafka producer with config: [$config]")
    producer = producerFactory()
    sendEventsJob = context.system.scheduler.schedule(config.initialDelay, config.interval, self, Protocol.SendEvents)
  }

  override def receive: Receive = {
    case Protocol.SendEvents =>
      eventQueue.dequeueAll(_ => true) foreach { case event@Event(topic, key, value) =>
        Future {
          producer.send(new ProducerRecord(topic.value, key, value)).get()
        } onFailure { case NonFatal(thrown) =>
          log.error(thrown, s"Publishing [${(key, value)}] to [$topic] failed!")
          self ! event
        }
      }
    case event: Event[_, _] => eventQueue.enqueue(event.asInstanceOf[Event[K, V]])
  }

  override def postStop(): Unit = {
    producer.closeQuietly
    sendEventsJob.cancel()
    super.postStop()
  }

}

object KafkaProducerClient {

  object Protocol {

    case object SendEvents

  }

  def apply[K, V](config: Config, producerName: ProducerName)(implicit factory: ActorRefFactory): ActorRef =
    apply(ProducerConfig(config, producerName), new jKafkaProducer[K, V](propertiesFrom(config.getConfig("kafka.producer.properties"))))

  def apply[K, V](config: ProducerConfig, producer: Producer[K, V])(implicit factory: ActorRefFactory): ActorRef =
    factory.actorOf(Props(new KafkaProducerClient(config, () => producer)), config.producerName.value)

}