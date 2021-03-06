package com.github.vipo.grpc4s

import scala.{Stream => _}
import com.github.vipo.grpc4s.ServiceDefinition.{Constr, Decons}

import scala.language.higherKinds
import io.grpc.MethodDescriptor.MethodType
import io.grpc.stub.{ServerCalls, StreamObserver}
import io.grpc.{ServerCallHandler, ServerServiceDefinition, StatusRuntimeException}
import scalaz.zio.Exit.{Failure, Success}
import scalaz.zio.stream.Take.{End, Fail, Value}
import scalaz.zio.{IO, Managed, Queue, RTS}
import scalaz.zio.stream.{Sink, Stream, Take}


object Zio {

  type GrpcIO[T] = IO[StatusRuntimeException, T]
  type GrpcStream[T] = Stream[StatusRuntimeException, T]

  private def serverCallHandler[Req, Res](methodType: MethodType, f: Req => Res, constr: Constr[Req, GrpcIO, GrpcStream], decons: Decons[Res, GrpcIO, GrpcStream])(
      implicit rts: RTS): ServerCallHandler[Array[Byte], Array[Byte]] =
    methodType match {
      case MethodType.UNARY => unary(f, constr, decons)
      case MethodType.CLIENT_STREAMING => clientStream(f, constr, decons)
      case MethodType.SERVER_STREAMING => serverStream(f, constr, decons)
      case _ => bidiStream(f, constr, decons)
    }

  private def unary[Req, Res](f: Req => Res, constr: Constr[Req, GrpcIO, GrpcStream], decons: Decons[Res, GrpcIO, GrpcStream])(
      implicit rts: RTS): ServerCallHandler[Array[Byte], Array[Byte]] =
    ServerCalls.asyncUnaryCall(
      (request: Array[Byte], observer: StreamObserver[Array[Byte]]) =>
        rts.unsafeRunAsync(decons(f(constr(RequestPure(request)))).unary) {
          case Success(value) =>
            observer.onNext(value)
            observer.onCompleted()
          case Failure(cause) =>
            observer.onError(cause.checked.headOption.getOrElse(cause.unchecked.head))
        }
    )

  private def clientStream[Req, Res](f: Req => Res, constr: Constr[Req, GrpcIO, GrpcStream], decons: Decons[Res, GrpcIO, GrpcStream])(
    implicit rts: RTS): ServerCallHandler[Array[Byte], Array[Byte]] =
    ServerCalls.asyncClientStreamingCall(
      (observer: StreamObserver[Array[Byte]]) => {
        new StreamObserver[Array[Byte]] {
          override def onNext(value: Array[Byte]): Unit = ???

          override def onError(t: Throwable): Unit = ???

          override def onCompleted(): Unit = ???
        }
      }
    )

  private def serverStream[Req, Res](f: Req => Res, constr: Constr[Req, GrpcIO, GrpcStream], decons: Decons[Res, GrpcIO, GrpcStream])(
      implicit rts: RTS): ServerCallHandler[Array[Byte], Array[Byte]] =
    ServerCalls.asyncServerStreamingCall(
      (request: Array[Byte], observer: StreamObserver[Array[Byte]]) => {
        val stream: GrpcStream[Array[Byte]] = decons(f(constr(RequestPure(request)))).stream
        val queue = stream.toQueue()
        def feed(t: Take[StatusRuntimeException, Array[Byte]]): IO[StatusRuntimeException, Unit] = t match {
          case End => IO.sync(observer.onCompleted())
          case Value(value) => IO.sync(observer.onNext(value))
          case Fail(er) => IO.sync(observer.onError(er))
        }
        rts.unsafeRun(queue.use(_.take.flatMap(feed).forever))
      }
    )

  private def bidiStream[Req, Res](f: Req => Res, constr: Constr[Req, GrpcIO, GrpcStream], decons: Decons[Res, GrpcIO, GrpcStream]): ServerCallHandler[Array[Byte], Array[Byte]] =
    ServerCalls.asyncBidiStreamingCall(
      (observer: StreamObserver[Array[Byte]]) =>
        observer
    )

  def build[Req, Res](f: Req => Res, definition: ServiceDefinition[Req, Res, GrpcIO, GrpcStream])(implicit rts: RTS): ServerServiceDefinition =
    definition.methods.foldLeft(ServerServiceDefinition.builder(definition.service)){
      case (builder, method) =>
        builder.addMethod(method._1, serverCallHandler(method._1.getType, f, method._2, method._3))
    }.build()

  implicit val conversions: Conversions[GrpcIO, GrpcStream] = new Conversions[GrpcIO, GrpcStream] {
    override def mapStream[F, T](stream: GrpcStream[F], f: F => T): GrpcStream[T] =
      stream.map(f)
    override def mapUnary[F, T](unary: GrpcIO[F], f: F => T): GrpcIO[T] =
      unary.map(f)
  }

}
