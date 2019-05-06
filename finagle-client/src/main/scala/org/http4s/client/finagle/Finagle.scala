package org.http4s
package client
package finagle

import cats.effect._
import cats.syntax.functor._
import com.twitter.finagle.{Http,Service}
import com.twitter.finagle.http.{Request => Req, Response=>Resp, Version, Method, RequestBuilder}
import com.twitter.util.{Return, Throw, Future}
import java.io.PipedInputStream
import fs2.io._

object Finagle {

  def allocate[F[_]](svc: Service[Req, Resp])(
      implicit F: ConcurrentEffect[F]): F[Client[F]] =
       F.delay(Client[F] { req =>
         Resource.liftF(toF(svc(toFinagleReq(req))).map(toHttp4sResp))
        })

  def resource[F[_]](dest: String)(
    implicit F: ConcurrentEffect[F]): Resource[F, Client[F]] = {
    Resource.make(F.delay(Http.newService(dest))){svc => toF(svc.close())}
    .flatMap(svc => Resource.liftF(allocate(svc)))
  }
  def toFinagleReq[F[_]](req: Request[F]):F[Req] = {
    val httpVersion = req.httpVersion match {
      case HttpVersion.`HTTP/1.0` => Version.Http10
      case _ => Version.Http11
    }
    val method = Method(req.method.name)
    val stream = new PipedInputStream()
    val finagleReq = RequestBuilder().url(req.uri).addHeaders(req.headers)
    req.body.through(toInputStream).compile.toList.headOption.map(finagleReq.build(method, _))
  }

  def toHttp4sResp[F[_]](resp: Resp): Response[F] = ???

  def toF[F[_], A](f: Future[A])(implicit F: Async[F]): F[A] = F.async{cb=>
    f.respond{
      case Return(value) => cb(Right(value))
      case Throw(exception) => cb(Left(exception))
    }
    ()
  }
}
