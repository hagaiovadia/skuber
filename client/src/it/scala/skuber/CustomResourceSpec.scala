package skuber

import akka.stream._
import akka.stream.scaladsl._
import skuber.apiextensions.CustomResourceDefinition
import org.scalatest.{BeforeAndAfterAll, Matchers}
import org.scalatest.concurrent.{Eventually, Futures, ScalaFutures}
import play.api.libs.json._
import skuber.ResourceSpecification.{ScaleSubresource, Subresources}
import scala.concurrent.duration._
import scala.language.postfixOps
import org.scalatest.Tag
import skuber.FutureUtil.FutureOps

/**
 * This tests making requests on custom resources based on a very simple custom resource type (TestResource) defined
 * here. (A TestResource consists of a desired replica count (spec) and corresponding actual replicas count (status))
 *
 * The tests cover the following interactions with Kubernetes via skuber:
 * - creating an appropriate Custom Resource Definition (CRD) for TestResource type
 * - retrieving the CRD
 * - creating TestResource custom resources
 * - retrieving  TestResource resources
 * - updating TestResource status (via status subresource)
 * - scaling TestResource replica count (via scale subresource)
 * - retrieval of list of TestResources
 * - watch events on TestResources
 * - deleting TestResources
 * - and finally the CRD is deleted
 *
 * Note the test requires the CustomResourceSubresources feature to be enabled (at time of writing it is an alpha feature)
 * on the Kubernetes cluster, so requires v1.10 or later of Kubernetes - on k8s v1.10 it needs a feature gate enabled,
 * but on v1.11 it is enabled by default.
 *
 * Initially tested using minikube initialised as follows:
 * 'minikube start --kubernetes-version=v1.10.0 --feature-gates=CustomResourceSubresources=true'
 * CustomResourceSpec tests are supported for k8s versions: 1.19,1.20,1.21
 */

class CustomResourceSpec extends K8SFixture with Eventually with Matchers with Futures with BeforeAndAfterAll with ScalaFutures {
  // Tagging the tests in order to exclude them in later CI k8s versions (1.22, 1.23, etc)
  object CustomResourceTag extends Tag("CustomResourceTag")

  // Convenient aliases for the custom object and list resource types to be passed to the skuber API methods
  type TestResource = CustomResource[TestResource.Spec, TestResource.Status]
  type TestResourceList = ListResource[TestResource]

  override def beforeAll(): Unit = {
    val k8s = k8sInit(config)
    k8s.create(TestResource.crd).withTimeout().futureValue
  }

  override def afterAll(): Unit = {
    val k8s = k8sInit(config)

    val results = k8s.delete[CustomResourceDefinition](TestResource.crd.name).withTimeout().recover { case _ => () }
    results.futureValue
    results.onComplete { _ =>
      k8s.close
      system.terminate().recover { case _ => () }.withTimeout().futureValue
    }

  }


  object TestResource {

    /*
     * Define the CustomResource model (spec and status), the implicit values that need to be passed to the
     * skuber API to enable it to send and receive TestResource/TestResourceList types, and the matching CRD
    */

    // TestResource model
    case class Spec(desiredReplicas: Int)

    case class Status(actualReplicas: Int)

    // skuber requires these implicit json formatters to marshal and unmarshal the TestResource spec and status fields.
    // The CustomResource json formatter will marshal/unmarshal these to/from "spec" and "status" subobjects
    // of the overall json representation of the resource.
    implicit val specFmt: Format[Spec] = Json.format[Spec]
    implicit val statusFmt: Format[Status] = Json.format[Status]

    // Resource definition: defines the details of the API for the resource type on Kubernetes
    // Must mirror the corresponding details in the associated CRD - see Kubernetes CRD documentation.
    // This needs to be passed implicitly to the skuber API to enable it to process TestResource requests.
    // The json paths in the Scale subresource must map to the replica fields in Spec and Status
    // respectively above
    implicit val testResourceDefinition = ResourceDefinition[TestResource](
      group = "test.skuber.io",
      version = "v1alpha1",
      kind = "SkuberTest",
      shortNames = List("test", "tests"), // not needed but handy if debugging the tests
      subresources = Some(Subresources()
        .withStatusSubresource // enable status subresource
        .withScaleSubresource(ScaleSubresource(".spec.desiredReplicas", ".status.actualReplicas")) // enable scale subresource
      )
    )

    // the following implicit values enable the scale and status methods on the skuber API to be called for this type
    // (these calls will be rejected unless the subresources are enabled on the CRD)
    implicit val scaleSubEnabled = CustomResource.scalingMethodsEnabler[TestResource]
    implicit val statusSubEnabled = CustomResource.statusMethodsEnabler[TestResource]

    // Construct an exportable Kubernetes CRD that mirrors the details in the matching implicit resource definition above -
    // the test will create it on Kubernetes so that the subsequent test requests can be handled by the cluster
    val crd = CustomResourceDefinition[TestResource]

    // Convenience method for constructing custom resources of the required type from a name snd a spec
    def apply(name: String, spec: Spec) = CustomResource[Spec, Status](spec).withName(name)
  }

  behavior of "CustomResource"

  override implicit val patienceConfig: PatienceConfig = PatienceConfig(10.second)

  def createNamedTestResource(k8s: FixtureParam, name: String, replicas: Int): CustomResource[TestResource.Spec, TestResource.Status] = {
    val testSpec = TestResource.Spec(replicas)
    val testResource1 = TestResource(name, testSpec)
    k8s.create(testResource1).withTimeout().futureValue
  }

  it should "get a crd" taggedAs (CustomResourceTag) in { k8s =>
    val getResource = k8s.get[CustomResourceDefinition](TestResource.crd.name).withTimeout().futureValue
    assert(getResource.name == TestResource.crd.name)
  }


  it should "create a new custom resource defined by the crd" taggedAs (CustomResourceTag) in { k8s =>
    val testResourceName1: String = java.util.UUID.randomUUID().toString
    val testResourceCreated = createNamedTestResource(k8s = k8s, name = testResourceName1, replicas = 1)

    val expectedSpec = TestResource.Spec(1)
    assert(testResourceCreated.name == testResourceName1)
    assert(testResourceCreated.spec == expectedSpec)

    val resourceGet = k8s.get[TestResource](testResourceName1).withTimeout().futureValue

    assert(resourceGet.name == testResourceName1)
    assert(resourceGet.spec.desiredReplicas == 1)
    assert(resourceGet.status.isEmpty)

  }

  it should "scale the desired replicas on the spec of the custom resource" taggedAs (CustomResourceTag) in { k8s =>
    val modifiedDesiredReplicas = 2
    val testResourceName1: String = java.util.UUID.randomUUID().toString
    createNamedTestResource(k8s = k8s, name = testResourceName1, replicas = 1)

    val currentScale = k8s.getScale[TestResource](testResourceName1).withTimeout().futureValue

    k8s.updateScale[TestResource](testResourceName1, currentScale.withSpecReplicas(modifiedDesiredReplicas)).withTimeout().futureValue
    val updated = k8s.get[TestResource](testResourceName1).withTimeout().futureValue
    assert(updated.spec.desiredReplicas == modifiedDesiredReplicas)
  }

  it should "update the status on the custom resource with a modified actual replicas count" taggedAs (CustomResourceTag) in { k8s =>
    val specReplicas = 1
    val testResourceName1: String = java.util.UUID.randomUUID().toString
    createNamedTestResource(k8s = k8s, name = testResourceName1, replicas = specReplicas)

    val modifiedActualReplicas = 3
    val status = TestResource.Status(modifiedActualReplicas)

    val testResource = k8s.get[TestResource](testResourceName1).withTimeout().futureValue
    val updated = k8s.updateStatus(testResource.withStatus(status)).withTimeout().futureValue
    updated.status shouldBe Some(status)

    val scale = k8s.getScale[TestResource](testResourceName1).withTimeout().futureValue
    scale.spec.replicas shouldBe Some(specReplicas)
    scale.status.get.replicas shouldBe modifiedActualReplicas
  }

  it should "delete the custom resource" taggedAs (CustomResourceTag) in { k8s =>
    val testResourceName1: String = java.util.UUID.randomUUID().toString
    createNamedTestResource(k8s = k8s, name = testResourceName1, replicas = 1)
    k8s.delete[TestResource](testResourceName1).withTimeout().futureValue

    whenReady(
      k8s.get[TestResource](testResourceName1).withTimeout().failed
    ) { result =>
      result shouldBe a[K8SException]
      result match {
        case ex: K8SException => ex.status.code shouldBe Some(404)
        case _ => assert(false)
      }
    }
  }

  it should "watch the custom resources" taggedAs (CustomResourceTag) in { k8s =>
    import skuber.api.client.{EventType, WatchEvent}
    import scala.collection.mutable.ListBuffer

    val testResourceName2: String = java.util.UUID.randomUUID().toString

    val testResource = TestResource(testResourceName2, TestResource.Spec(1))

    val trackedEvents = ListBuffer.empty[WatchEvent[TestResource]]
    val trackEvents: Sink[WatchEvent[TestResource], _] = Sink.foreach { event =>
      trackedEvents += event
    }

    def getCurrentResourceVersion: String = k8s.list[TestResourceList].withTimeout().futureValue.resourceVersion


    def watchAndTrackEvents(sinceVersion: String) = {
      val crEventSource = k8s.watchAll[TestResource](sinceResourceVersion = Some(sinceVersion)).withTimeout().futureValue
      crEventSource
        .viaMat(KillSwitches.single)(Keep.right)
        .toMat(trackEvents)(Keep.both).run()

    }

    def createTestResource = k8s.create(testResource).withTimeout().futureValue

    def deleteTestResource(resource: String) = k8s.delete[TestResource](resource).withTimeout().futureValue

    val killSwitch: UniqueKillSwitch = {
      val (kill, _) = watchAndTrackEvents(getCurrentResourceVersion)
      createTestResource
      deleteTestResource(testResourceName2)
      kill
    }

    eventually(timeout(30.seconds), interval(3.seconds)) {
      trackedEvents.size shouldBe 2
      trackedEvents(0)._type shouldBe EventType.ADDED
      trackedEvents(0)._object.name shouldBe testResource.name
      trackedEvents(0)._object.spec shouldBe testResource.spec
      trackedEvents(1)._type shouldBe EventType.DELETED
    }

    // cleanup
    killSwitch.shutdown()
    assert(true)
  }

}
