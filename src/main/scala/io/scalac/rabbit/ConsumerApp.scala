package io.scalac.rabbit

import akka.actor.ActorSystem
import akka.pattern.ask
import akka.stream.actor.ActorPublisher
import akka.stream.MaterializerSettings
import akka.stream.scaladsl2.FlowMaterializer
import akka.util.Timeout
import com.rabbitmq.client.Connection
import com.typesafe.scalalogging.slf4j.LazyLogging
import io.scalac.rabbit.flow._
import io.scalac.rabbit.flow.RabbitConnectionActor.Connect
import java.net.InetSocketAddress
import QueueRegistry._
import scala.concurrent.duration._
import scala.language.postfixOps
import org.reactivestreams.Publisher
import akka.stream.scaladsl2.ProcessorFlow
import akka.stream.scaladsl2.FlowGraph
import akka.stream.scaladsl2.FlowFrom
import akka.stream.scaladsl2.FlowWithSource
import akka.stream.scaladsl2.PublisherSink
import akka.stream.scaladsl2.SubscriberSource

object QueueRegistry {

  val INBOUND_EXCHANGE = "censorship.inbound.exchange"
  val INBOUND_QUEUE = "censorship.inbound.queue"
    
  val OUT_OK_EXCHANGE = "censorship.ok.exchange"
  val OUT_OK_QUEUE = "censorship.ok.queue"
  
  val OUT_NOK_EXCHANGE = "censorship.nok.exchange"
  val OUT_NOK_QUEUE = "censorship.nok.queue"
  
  val IN_BINDING = RabbitBinding(INBOUND_EXCHANGE, INBOUND_QUEUE)
  val OUT_OK_BINDING = RabbitBinding(OUT_OK_EXCHANGE, OUT_OK_QUEUE)
  val OUT_NOK_BINDING = RabbitBinding(OUT_NOK_EXCHANGE, OUT_NOK_QUEUE)
}

/**
 * This is the message processing specific for a domain. Here we are only applying some 
 * simple filtering, logging and mapping, but the idea is that this part as the meat of your application.
 * 
 * Depending on your domain you could for example call some external services or actors here.
 */
object MyDomainProcessing extends LazyLogging {
  
  /**
   * Tuple assigning a RabbitMQ exchange name to a stream Producer.
   */
  type ExchangeMapping = (String, FlowWithSource[CensoredMessage, CensoredMessage])
  
  def apply(): ProcessorFlow[RabbitMessage, ExchangeMapping] = FlowFrom[RabbitMessage].
  
    // acknowledge and pass on
    map { msg =>
      msg.ack()
      msg
    }.
    
    // extract message body
    map { _.body.utf8String }.
    
    // do something time consuming - like go to sleep
    // then log the message text
    map { msg => 
      Thread.sleep(2000)
      logger.info(msg)
      msg 
    }.

    // call domain service
    map { CensorshipService.classify }.
    
    // split by classification and assign an outbound exchange
    groupBy { 
      case MessageSafe(msg) => OUT_OK_EXCHANGE
      case MessageThreat(msg) => OUT_NOK_EXCHANGE
    }
}

object ConsumerApp extends App {

  implicit val timeout = Timeout(2 seconds)
  
  implicit val actorSystem = ActorSystem("rabbit-akka-stream")
  
  implicit val executor = actorSystem.dispatcher
  
  implicit val materializer = FlowMaterializer(MaterializerSettings(actorSystem))
  
  val connectionActor = actorSystem.actorOf(
    RabbitConnectionActor.props(new InetSocketAddress("127.0.0.1", 5672))
  )
  
  
  /*
   * Ask for a connection and start processing.
   */
  (connectionActor ? Connect).mapTo[Connection] map { implicit conn =>
    
    val rabbitConsumer = ActorPublisher(actorSystem.actorOf(RabbitConsumerActor.props(IN_BINDING)))
    
    val domainProcessingDuct = MyDomainProcessing()
    
    val okPublisherFlow = new RabbitPublisher(OUT_OK_BINDING).flow
    val nokPublisherFlow = new RabbitPublisher(OUT_NOK_BINDING).flow
    
    val publisherDuct: String => ProcessorFlow[String, Unit] = ex => ex match {
      case OUT_OK_EXCHANGE => okPublisherFlow
      case OUT_NOK_EXCHANGE => nokPublisherFlow
    }
    
    /*
     * connect flows with ducts and consume
     */    
    val mainGraph = FlowGraph { implicit b =>
      domainProcessingDuct map { 
        case (exchange, producer) => 
        
        // start a new flow for each message type
        val innerGraph = FlowGraph { implicit c =>
        
          producer
          
          // extract the message
          .map(_.message) 
          
          // add the outbound publishing duct
          .append(publisherDuct(exchange))
        }.run()
        
        val pub = PublisherSink[String].publisher(innerGraph)
      }
      
    }.run()
    
    SubscriberSource[RabbitMessage].subscriber(mainGraph)
    
//    Flow(rabbitConsumer) append domainProcessingDuct map { 
//      case (exchange, producer) => 
//        
//        // start a new flow for each message type
//        Flow(producer)
//        
//          // extract the message
//          .map(_.message) 
//          
//          // add the outbound publishing duct
//          .append(publisherDuct(exchange))
//          
//          // and start the flow
//          .consume()
//        
//    } consume()
  }

}