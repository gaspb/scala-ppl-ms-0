package org.highjack.scalapipeline.kafka

import java.util.concurrent.atomic.AtomicLong

import akka.{Done, NotUsed}
import akka.actor.{Actor, ActorLogging, ActorRef, ActorSystem, Props}
import akka.kafka.ConsumerMessage.{CommittableMessage, CommittableOffsetBatch}
import akka.kafka.{ConsumerMessage, KafkaConsumerActor, ProducerMessage, Subscriptions}
import akka.kafka.scaladsl.Consumer.DrainingControl
import akka.kafka.scaladsl.{Consumer, Producer, Transactional}
import akka.stream.scaladsl.{Keep, RestartSource, Sink, Source}
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.{Metric, MetricName, TopicPartition}
import sample.scaladsl.AkkaKafkaProducer

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

class AkkaKafkaConsumerDemos {

}

// Consume messages and store a representation, including offset, in DB
object ExternalOffsetStorageExample extends AkkaKafkaConsumer {
    def main(args: Array[String]): Unit = {
        // format: off
        // #plainSource
        val db = new OffsetStore
        val control = db.loadOffset().map { fromOffset =>
            Consumer
                .plainSource(
                    consumerSettings,
                    Subscriptions.assignmentWithOffset(
                        new TopicPartition("topic1", /* partition = */ 0) -> fromOffset
                    )
                )
                .mapAsync(1)(db.businessLogicAndStoreOffset)
                .to(Sink.ignore)
                .run()
        }
        // #plainSource
        control.foreach(c => terminateWhenDone(c.shutdown()))
    }

    // #plainSource

    class OffsetStore {
        // #plainSource

        private val offset = new AtomicLong

        // #plainSource
        def businessLogicAndStoreOffset(record: ConsumerRecord[String, Array[Byte]]): Future[Done] = // ...
        // #plainSource
        {
            println(s"DB.save: ${record.value}")
            offset.set(record.offset)
            Future.successful(Done)
        }

        // #plainSource
        def loadOffset(): Future[Long] = // ...
        // #plainSource
            Future.successful(offset.get)

        // #plainSource
    }
    // #plainSource
    // format: on

}

// Consume messages at-most-once
object AtMostOnceExample extends AkkaKafkaConsumer {
    def main(args: Array[String]): Unit = {
        // #atMostOnce
        val control =
            Consumer
                .atMostOnceSource(consumerSettings, Subscriptions.topics("topic1"))
                .mapAsync(1)(record => business(record.key, record.value()))
                .to(Sink.foreach(it => println(s"Done with $it")))
                .run()
        // #atMostOnce

        terminateWhenDone(control.shutdown())
    }
    // #atMostOnce

    def business(key: String, value: Array[Byte]): Future[Done] = ???
    // #atMostOnce
}

// Consume messages at-least-once
object AtLeastOnceExample extends AkkaKafkaConsumer {
    def main(args: Array[String]): Unit = {
        // #atLeastOnce
        val control =
            Consumer
                .committableSource(consumerSettings, Subscriptions.topics("topic1"))
                .mapAsync(10) { msg =>
                    business(msg.record.key, msg.record.value).map(_ => msg.committableOffset)
                }
                .mapAsync(5)(offset => offset.commitScaladsl())
                .toMat(Sink.ignore)(Keep.both)
                .mapMaterializedValue(DrainingControl.apply)
                .run()
        // #atLeastOnce

        terminateWhenDone(control.drainAndShutdown())
    }
    // format: off
    // #atLeastOnce

    def business(key: String, value: Array[Byte]): Future[Done] = ???
    // #atLeastOnce
    // format: on
}

// Consume messages at-least-once, and commit in batches
object AtLeastOnceWithBatchCommitExample extends AkkaKafkaConsumer {
    def main(args: Array[String]): Unit = {

        // #atLeastOnceBatch
        val control =
            Consumer
                .committableSource(consumerSettings, Subscriptions.topics("topic1"))
                .mapAsync(1) { msg =>
                    business(msg.record.key, msg.record.value)
                        .map(_ => msg.committableOffset)
                }
                .batch(
                    max = 20,
                    CommittableOffsetBatch.apply
                )(_.updated(_))
                .mapAsync(3)(_.commitScaladsl())
                .toMat(Sink.ignore)(Keep.both)
                .mapMaterializedValue(DrainingControl.apply)
                .run()
        // #atLeastOnceBatch

        terminateWhenDone(control.drainAndShutdown())
    }

    def business(key: String, value: Array[Byte]): Future[Done] = ???
}

// Connect a Consumer to Producer
object ConsumerToProducerSinkExample extends AkkaKafkaConsumer {
    def main(args: Array[String]): Unit = {
        //format: off
        // #consumerToProducerSink
        val control =
        Consumer
            .committableSource(consumerSettings, Subscriptions.topics("topic1", "topic2"))
            .map { msg =>
                ProducerMessage.Message[String, Array[Byte], ConsumerMessage.CommittableOffset](
                    new ProducerRecord("targetTopic", msg.record.value),
                    msg.committableOffset
                )
            }
            .toMat(Producer.commitableSink(producerSettings))(Keep.both)
            .mapMaterializedValue(DrainingControl.apply)
            .run()
        // #consumerToProducerSink
        //format: on
        control.drainAndShutdown()
    }
}

// Connect a Consumer to Producer
object ConsumerToProducerFlowExample extends AkkaKafkaConsumer {
    def main(args: Array[String]): Unit = {
        // #consumerToProducerFlow
        val control = Consumer
            .committableSource(consumerSettings, Subscriptions.topics("topic1"))
            .map { msg =>
                ProducerMessage.Message[String, Array[Byte], ConsumerMessage.CommittableOffset](
                    new ProducerRecord("topic2", msg.record.value),
                    passThrough = msg.committableOffset
                )
            }
            .via(Producer.flexiFlow(producerSettings))
            .mapAsync(producerSettings.parallelism) { result =>
                val committable = result.passThrough
                committable.commitScaladsl()
            }
            .toMat(Sink.ignore)(Keep.both)
            .mapMaterializedValue(DrainingControl.apply)
            .run()
        // #consumerToProducerFlow

        terminateWhenDone(control.drainAndShutdown())
    }
}

// Connect a Consumer to Producer, and commit in batches
object ConsumerToProducerWithBatchCommitsExample extends AkkaKafkaConsumer {
    def main(args: Array[String]): Unit = {
        // #consumerToProducerFlowBatch
        val control = Consumer
            .committableSource(consumerSettings, Subscriptions.topics("topic1"))
            .map(
                msg =>
                    ProducerMessage.Message[String, Array[Byte], ConsumerMessage.CommittableOffset](
                        new ProducerRecord("topic2", msg.record.value),
                        msg.committableOffset
                    )
            )
            .via(Producer.flexiFlow(producerSettings))
            .map(_.passThrough)
            .batch(max = 20, CommittableOffsetBatch.apply)(_.updated(_))
            .mapAsync(3)(_.commitScaladsl())
            .toMat(Sink.ignore)(Keep.both)
            .mapMaterializedValue(DrainingControl.apply)
            .run()
        // #consumerToProducerFlowBatch

        terminateWhenDone(control.drainAndShutdown())
    }
}

// Connect a Consumer to Producer, and commit in batches
object ConsumerToProducerWithBatchCommits2Example extends AkkaKafkaConsumer {
    def main(args: Array[String]): Unit = {
        val source = Consumer
            .committableSource(consumerSettings, Subscriptions.topics("topic1"))
            .map(
                msg =>
                    ProducerMessage.Message(
                        new ProducerRecord[String, Array[Byte]]("topic2", msg.record.value),
                        msg.committableOffset
                    )
            )
            .via(Producer.flexiFlow(producerSettings))
            .map(_.passThrough)
        val done =
        // #groupedWithin
            source
                .groupedWithin(10, 5.seconds)
                .map(CommittableOffsetBatch(_))
                .mapAsync(3)(_.commitScaladsl())
                // #groupedWithin
                .runWith(Sink.ignore)

        terminateWhenDone(done)
    }
}

// Backpressure per partition with batch commit
object ConsumerWithPerPartitionBackpressure extends AkkaKafkaConsumer {
    def main(args: Array[String]): Unit = {
        // #committablePartitionedSource
        val control = Consumer
            .committablePartitionedSource(consumerSettings, Subscriptions.topics("topic1"))
            .flatMapMerge(maxPartitions, _._2)
            .via(business)
            .map(_.committableOffset)
            .batch(max = 100, CommittableOffsetBatch.apply)(_.updated(_))
            .mapAsync(3)(_.commitScaladsl())
            .to(Sink.ignore)
            .run()
        // #committablePartitionedSource

        terminateWhenDone(control.shutdown())
    }
}

// Flow per partition
object ConsumerWithIndependentFlowsPerPartition extends AkkaKafkaConsumer {
    def main(args: Array[String]): Unit = {
        //Process each assigned partition separately
        // #committablePartitionedSource-stream-per-partition
        val control = Consumer
            .committablePartitionedSource(consumerSettings, Subscriptions.topics("topic1"))
            .map {
                case (topicPartition, source) =>
                    source
                        .via(business)
                        .mapAsync(1)(_.committableOffset.commitScaladsl())
                        .runWith(Sink.ignore)
            }
            .mapAsyncUnordered(maxPartitions)(identity)
            .to(Sink.ignore)
            .run()
        // #committablePartitionedSource-stream-per-partition
        terminateWhenDone(control.shutdown())
    }
}

// Join flows based on automatically assigned partitions
object ConsumerWithOtherSource extends AkkaKafkaConsumer {
    def main(args: Array[String]): Unit = {
        // #committablePartitionedSource3
        type Msg = CommittableMessage[String, Array[Byte]]

        def zipper(left: Source[Msg, _], right: Source[Msg, _]): Source[(Msg, Msg), NotUsed] = ???

        Consumer
            .committablePartitionedSource(consumerSettings, Subscriptions.topics("topic1"))
            .map {
                case (topicPartition, source) =>
                    // get corresponding partition from other topic
                    val otherTopicPartition = new TopicPartition("otherTopic", topicPartition.partition())
                    val otherSource = Consumer.committableSource(consumerSettings, Subscriptions.assignment(otherTopicPartition))
                    zipper(source, otherSource)
            }
            .flatMapMerge(maxPartitions, identity)
            .via(business)
            //build commit offsets
            .batch(
            max = 20,
            seed = {
                case (left, right) =>
                    (
                        CommittableOffsetBatch(left.committableOffset),
                        CommittableOffsetBatch(right.committableOffset)
                    )
            }
        )(
            aggregate = {
                case ((batchL, batchR), (l, r)) =>
                    batchL.updated(l.committableOffset)
                    batchR.updated(r.committableOffset)
                    (batchL, batchR)
            }
        )
            .mapAsync(1) { case (l, r) => l.commitScaladsl().map(_ => r) }
            .mapAsync(1)(_.commitScaladsl())
            .runWith(Sink.ignore)
        // #committablePartitionedSource3
    }
}

//externally controlled kafka consumer
object ExternallyControlledKafkaConsumer extends AkkaKafkaConsumer {
    def main(args: Array[String]): Unit = {
        // #consumerActor
        //Consumer is represented by actor
        val consumer: ActorRef = system.actorOf(KafkaConsumerActor.props(consumerSettings))

        //Manually assign topic partition to it
        val controlPartition1 = Consumer
            .plainExternalSource[String, Array[Byte]](
            consumer,
            Subscriptions.assignment(new TopicPartition("topic1", 1))
        )
            .via(business)
            .to(Sink.ignore)
            .run()

        //Manually assign another topic partition
        val controlPartition2 = Consumer
            .plainExternalSource[String, Array[Byte]](
            consumer,
            Subscriptions.assignment(new TopicPartition("topic1", 2))
        )
            .via(business)
            .to(Sink.ignore)
            .run()

        consumer ! KafkaConsumerActor.Stop
        // #consumerActor
        terminateWhenDone(controlPartition1.shutdown().flatMap(_ => controlPartition2.shutdown()))
    }
}

object ConsumerMetrics extends AkkaKafkaConsumer {
    def main(args: Array[String]): Unit = {
        // #consumerMetrics
        val control: Consumer.Control = Consumer
            .plainSource(consumerSettings, Subscriptions.assignment(new TopicPartition("topic1", 1)))
            .via(business)
            .to(Sink.ignore)
            .run()

        val metrics: Future[Map[MetricName, Metric]] = control.metrics
        metrics.foreach(map => println(s"metrics: ${map}"))
        // #consumerMetrics
    }
}

class RestartingStream extends AkkaKafkaConsumer {

    def createStream(): Unit =
    //#restartSource
        RestartSource
            .withBackoff(
                minBackoff = 3.seconds,
                maxBackoff = 30.seconds,
                randomFactor = 0.2
            ) { () =>
                Source.fromFuture {
                    val source = Consumer.plainSource(consumerSettings, Subscriptions.topics("topic1"))
                    source
                        .via(business)
                        .watchTermination() {
                            case (consumerControl, futureDone) =>
                                futureDone
                                    .flatMap { _ =>
                                        consumerControl.shutdown()
                                    }
                                    .recoverWith { case _ => consumerControl.shutdown() }
                        }
                        .runWith(Sink.ignore)
                }
            }
            .runWith(Sink.ignore)
    //#restartSource
}

object RebalanceListenerExample extends AkkaKafkaConsumer {
    //#withRebalanceListenerActor
    import akka.kafka.TopicPartitionsAssigned
    import akka.kafka.TopicPartitionsRevoked

    class RebalanceListener extends Actor with ActorLogging {
        def receive: Receive = {
            case TopicPartitionsAssigned(sub, assigned) ⇒
                log.info("Assigned: {}", assigned)

            case TopicPartitionsRevoked(sub, revoked) ⇒
                log.info("Revoked: {}", revoked)
        }
    }

    //#withRebalanceListenerActor

    def createActor(implicit system: ActorSystem): Source[ConsumerRecord[String, Array[Byte]], Consumer.Control] = {
        //#withRebalanceListenerActor
        val rebalanceListener = system.actorOf(Props[RebalanceListener])

        val subscription = Subscriptions
            .topics(Set("topic"))
            // additionally, pass the actor reference:
            .withRebalanceListener(rebalanceListener)

        // use the subscription as usual:
        Consumer
            .plainSource(consumerSettings, subscription)
        //#withRebalanceListenerActor
    }

}

// Shutdown via Consumer.Control
object ShutdownPlainSourceExample extends AkkaKafkaConsumer {

    def main(args: Array[String]): Unit = {
        val offset = 123456L
        // #shutdownPlainSource
        val (consumerControl, streamComplete) =
            Consumer
                .plainSource(consumerSettings,
                    Subscriptions.assignmentWithOffset(
                        new TopicPartition("topic1", 0) -> offset
                    ))
            //    .mapAsync(1)(/*businessLogic*/_)
                .toMat(Sink.ignore)(Keep.both)
                .run()

        consumerControl.shutdown()
        // #shutdownPlainSource
        terminateWhenDone(streamComplete)
    }

}

// Shutdown when batching commits
object ShutdownCommitableSourceExample extends AkkaKafkaConsumer {
    def main(args: Array[String]): Unit = {
        // #shutdownCommitableSource
        val drainingControl =
            Consumer
                .committableSource(consumerSettings, Subscriptions.topics("topic1"))
                .mapAsync(1) { msg =>
                    //businessLogic(msg.record).map(_ => msg.committableOffset)
                  Future(msg.committableOffset) //TODO
                }
                .batch(max = 20, first => CommittableOffsetBatch(first)) { (batch, elem) =>
                    batch.updated(elem)
                }
                .mapAsync(3)(_.commitScaladsl())
                .toMat(Sink.ignore)(Keep.both)
                .mapMaterializedValue(DrainingControl.apply)
                .run()

        val streamComplete = drainingControl.drainAndShutdown()

        // #shutdownCommitableSource

        terminateWhenDone(streamComplete)
    }
}
object TransactionsSink extends AkkaKafkaConsumer {
    def main(args: Array[String]): Unit = {
        // #transactionalSink
        val control =
            Transactional
                .source(consumerSettings, Subscriptions.topics("source-topic"))
                .via(business)
                .map { msg =>
                    ProducerMessage.Message(new ProducerRecord[String, Array[Byte]]("sink-topic", msg.record.value),
                        msg.partitionOffset)
                }
                .to(Transactional.sink(producerSettings, "transactional-id"))
                .run()

        // ...

        control.shutdown()
        // #transactionalSink
        terminateWhenDone(control.shutdown())
    }
}