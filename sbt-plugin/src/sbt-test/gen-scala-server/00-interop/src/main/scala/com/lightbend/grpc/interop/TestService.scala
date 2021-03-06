package com.lightbend.grpc.interop

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.reflect.ClassTag
import scala.collection.immutable

import akka.grpc.scaladsl.GrpcMarshalling
import akka.grpc.GrpcServiceException

import akka.NotUsed
import akka.NotUsed
import akka.NotUsed
import akka.grpc._
import akka.stream.scaladsl.{Flow, Source}
import akka.stream.Materializer

import com.google.protobuf.ByteString
import com.google.protobuf.empty.Empty
import io.grpc.{ Status, StatusRuntimeException }

// Generated by our plugin
import io.grpc.testing.integration.test.TestService
import io.grpc.testing.integration.messages._

object TestServiceImpl {
  val parametersToResponseFlow: Flow[ResponseParameters, StreamingOutputCallResponse, NotUsed] =
    Flow[ResponseParameters]
      .map { parameters =>
        StreamingOutputCallResponse(
          Some(Payload(body = ByteString.copyFrom(new Array[Byte](parameters.size)))))
      }
}

// Implementation of the generated interface
class TestServiceImpl(implicit ec: ExecutionContext, mat: Materializer) extends TestService {

  import TestServiceImpl._

  override def emptyCall(req: Empty) = Future.successful(Empty())

  override def unaryCall(req: SimpleRequest): Future[SimpleResponse] = {
    req.responseStatus match {
      case None =>
        Future.successful(
          SimpleResponse(
            Some(Payload(req.responseType, ByteString.copyFrom(new Array[Byte](req.responseSize))))))
      case Some(requestStatus) =>
        val responseStatus = Status.fromCodeValue(requestStatus.code).withDescription(requestStatus.message)
        Future.failed(throw new GrpcServiceException(responseStatus))
    }
  }

  override def cacheableUnaryCall(in: SimpleRequest): Future[SimpleResponse] = ???

  override def fullDuplexCall(in: Source[StreamingOutputCallRequest, NotUsed]): Source[StreamingOutputCallResponse, NotUsed] =
    in.map(req => {
      req.responseStatus.foreach(reqStatus =>
        throw new GrpcServiceException(
          Status.fromCodeValue(reqStatus.code).withDescription(reqStatus.message)))
      req
    }).mapConcat(
      _.responseParameters.to[immutable.Seq]).via(parametersToResponseFlow)

  override def halfDuplexCall(in: Source[StreamingOutputCallRequest, NotUsed]): Source[StreamingOutputCallResponse, NotUsed] = ???

  override def streamingInputCall(in: Source[StreamingInputCallRequest, NotUsed]): Future[StreamingInputCallResponse] = {
    in
      .map(_.payload.map(_.body.size).getOrElse(0))
      .runFold(0)(_ + _)
      .map { sum ⇒
        StreamingInputCallResponse(sum)
      }
  }
  override def streamingOutputCall(in: StreamingOutputCallRequest): Source[StreamingOutputCallResponse, NotUsed] =
    Source(in.responseParameters.to[immutable.Seq]).via(parametersToResponseFlow)

  override def unimplementedCall(in: Empty): Future[Empty] = ???
}
