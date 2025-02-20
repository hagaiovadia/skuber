package skuber.examples.argo

import java.util.UUID.randomUUID
import akka.actor.ActorSystem
import play.api.libs.functional.syntax.unlift
import play.api.libs.json.{Format, JsPath, Json}
import skuber.ResourceSpecification.{Names, Scope}
import skuber.api.client.LoggingContext
import skuber.examples.argo.EventBus.{EventBusSetList, Native, Nats, eventBusFmt, eventBusListFmt, rsDef, rsListDef}
import skuber.json.format.{ListResourceFormat, objFormat}
import skuber.{ListResource, NonCoreResourceSpecification, ObjectMeta, ObjectResource, ResourceDefinition, k8sInit}
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContextExecutor}

object EventBusExample extends App {
  // for testing
  // kubectl create ns argo-eventbus
  // kubectl apply -f https://raw.githubusercontent.com/argoproj-labs/argo-eventbus/stable/manifests/install.yaml

  implicit val system: ActorSystem = ActorSystem()
  implicit val dispatcher: ExecutionContextExecutor = system.dispatcher
  val k8s = k8sInit

  val eventBusResource1 = EventBus(randomUUID().toString)
  val k8sArgo = k8s.usingNamespace("argo-eventbus")
  val cr = k8sArgo.create(eventBusResource1)(eventBusFmt, rsDef, LoggingContext.lc)

  Await.result(cr, 30.seconds)
  val ls = k8sArgo.list[EventBusSetList]()(eventBusListFmt, rsListDef, LoggingContext.lc).map { eventsBusList =>
    println(eventsBusList.mkString("\n"))
  }
  Await.result(ls, 30.seconds)

  k8s.close
  k8sArgo.close
  Await.result(system.terminate(), 10.seconds)

}

case class EventBus(val kind: String = "EventBus",
                    override val apiVersion: String = "argoproj.io/v1alpha1",
                    val metadata: ObjectMeta = ObjectMeta(),
                    spec: Option[EventBus.Spec] = Some(EventBus.Spec(EventBus.Nats(EventBus.Native())))) extends ObjectResource {

}

object EventBus {

  val specification=NonCoreResourceSpecification(
    apiGroup = "argoproj.io",
    version = "v1alpha1",
    scope = Scope.Namespaced,
    names = Names(
      plural = "eventbus",
      singular = "eventbus",
      kind = "EventBus",
      shortNames = List("eb")
    )
  )
  type EventBusSetList = ListResource[EventBus]
  implicit val rsDef = new ResourceDefinition[EventBus] { def spec=specification }
  implicit val rsListDef = new ResourceDefinition[EventBusSetList] { def spec=specification }

  def apply(name: String) : EventBus = EventBus(metadata=ObjectMeta(name=name))

  case class Spec(nats: Nats)

  case class Nats(native: Native)

  case class Persistence(storageClassName: String,
                         accessMode: String,
                         volumeSize: String)

  case class Native(replicas: Option[Int] = None,
                    auth: Option[String] = None,
                    persistence: Option[Persistence] = None)

  implicit val persistenceFmt: Format[Persistence] = Json.format[Persistence]

  implicit val nativeFmt: Format[Native] = Json.format[Native]
  implicit val natsFmt: Format[Nats] = Json.format[Nats]
  implicit val specFmt: Format[Spec] = Json.format[Spec]

  implicit lazy val eventBusFmt: Format[EventBus] = (
    objFormat and
      (JsPath \ "spec").formatNullable[EventBus.Spec]
    )(EventBus.apply _, unlift(EventBus.unapply))

  implicit val eventBusListFmt: Format[EventBusSetList] = ListResourceFormat[EventBus]

}