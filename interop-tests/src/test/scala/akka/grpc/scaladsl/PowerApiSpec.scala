/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.grpc.scaladsl

import com.typesafe.config.ConfigFactory
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

import scala.concurrent.Future
import scala.concurrent.duration._

import akka.actor.ActorSystem
import akka.grpc.GrpcClientSettings
import akka.grpc.internal.{ GrpcEntityHelpers, GrpcProtocolNative, GrpcResponseHelpers, HeaderMetadataImpl, Identity }
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.server.Directives
import example.myapp.helloworld.grpc.helloworld._
import io.grpc.Status
import akka.grpc.GrpcResponseMetadata
import akka.http.scaladsl.Http
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
import akka.testkit.TestKit
import example.myapp.helloworld.grpc.helloworld.HelloReply
import example.myapp.helloworld.grpc.helloworld.GreeterServiceClient
import example.myapp.helloworld.grpc.helloworld.GreeterServicePowerApiHandler
import example.myapp.helloworld.grpc.helloworld.HelloRequest
import org.scalatest.BeforeAndAfter
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.Span
import org.scalatest.wordspec.AnyWordSpecLike

class PowerApiSpecNetty extends PowerApiSpec("netty")
class PowerApiSpecAkkaHttp extends PowerApiSpec("akka-http")

abstract class PowerApiSpec(backend: String)
    extends TestKit(ActorSystem(
      "GrpcExceptionHandlerSpec",
      ConfigFactory.parseString(s"""akka.grpc.client."*".backend = "$backend" """).withFallback(ConfigFactory.load())))
    with AnyWordSpecLike
    with Matchers
    with ScalaFutures
    with Directives
    with BeforeAndAfter
    with BeforeAndAfterAll {

  override implicit val patienceConfig = PatienceConfig(5.seconds, Span(10, org.scalatest.time.Millis))

  val numberOfRepliesOverStreams = 5

  val server =
    Http()
      .newServerAt("localhost", 0)
      .bind(GreeterServicePowerApiHandler(new PowerGreeterServiceImpl(numberOfRepliesOverStreams)))
      .futureValue

  var client: GreeterServiceClient = _

  after {
    if (client != null && !client.closed.isCompleted) {
      client.close().futureValue
    }
  }
  override protected def afterAll(): Unit = {
    server.terminate(3.seconds)
    super.afterAll()
  }

  "The power API" should {
    "successfully pass metadata from client to server" in {
      client = GreeterServiceClient(
        GrpcClientSettings.connectToServiceAt("localhost", server.localAddress.getPort).withTls(false))

      client
        .sayHello()
        // No authentication
        .invoke(HelloRequest("Alice"))
        .futureValue
        .message should be("Hello, Alice (not authenticated)")

      client.sayHello().addHeader("Authorization", "foo").invoke(HelloRequest("Alice")).futureValue.message should be(
        "Hello, Alice (authenticated)")
    }

    "successfully pass metadata from server to client" in {
      implicit val serializer = GreeterService.Serializers.HelloReplySerializer
      val specialServer =
        Http()
          .newServerAt("localhost", 0)
          .bind(path(GreeterService.name / "SayHello") {
            implicit val writer = GrpcProtocolNative.newWriter(Identity)
            val trailingMetadata = new HeaderMetadataImpl(List(RawHeader("foo", "bar")))
            complete(
              GrpcResponseHelpers(
                Source.single(HelloReply("Hello there!")),
                trail = Source.single(GrpcEntityHelpers.trailer(Status.OK, trailingMetadata)))
                .addHeader(RawHeader("baz", "qux")))
          })
          .futureValue

      client = GreeterServiceClient(
        GrpcClientSettings.connectToServiceAt("localhost", specialServer.localAddress.getPort).withTls(false))

      val response = client
        .sayHello()
        // No authentication
        .invokeWithMetadata(HelloRequest("Alice"))
        .futureValue

      response.value.message should be("Hello there!")
      response.headers.getText("baz").get should be("qux")
      response.trailers.futureValue.getText("foo").get should be("bar")
    }

    "(on streamed calls) redeem the headers future as soon as they're available (and trailers future when trailers arrive)" in {
      // invoking streamed calls using the power API materializes a Future[GrpcResponseMetadata]
      // that should redeem as soon as the HEADERS is consumed. Then, the GrpcResponseMetadata instance
      // contains another Future that will redeem when receiving the trailers.
      client = GreeterServiceClient(
        GrpcClientSettings.connectToServiceAt("localhost", server.localAddress.getPort).withTls(false))

      val request = HelloRequest("Alice")
      val responseSource: Source[HelloReply, Future[GrpcResponseMetadata]] =
        client.itKeepsReplying().invokeWithMetadata(request)
      val firstItem: CountDownLatch = new CountDownLatch(1)
      val fiveItems: CountDownLatch = new CountDownLatch(5)
      val headers: Future[GrpcResponseMetadata] =
        responseSource
          .map { x =>
            firstItem.countDown()
            fiveItems.countDown()
            x
          }
          .to(Sink.ignore)
          .run

      headers.isCompleted should be(false)

      // As soon as the first item has arrived, the headers future
      // should be redeemed but the fiveItems latch should still be open
      // and the trailers future should not have been redeemed yet.
      firstItem.await(500, TimeUnit.MILLISECONDS)
      val trailers = headers.futureValue.trailers
      fiveItems.getCount.toInt should be < 5

      trailers.isCompleted should be(false)

      fiveItems.await(500, TimeUnit.MILLISECONDS)

      trailers.futureValue // the trailers future eventually completes

    }
  }

}
