package org.http4s
package client
package finagle

import cats.effect._
import cats.syntax.functor._
import com.twitter.finagle.{Http,Service}
import com.twitter.finagle.http.{Request => Req, Response=>Resp, Method, RequestBuilder}
import com.twitter.util.{Return, Throw, Future}
import cats.syntax.flatMap._
import com.twitter.io.Buf

object Finagle {

  def allocate[F[_]](svc: Service[Req, Resp])(
      implicit F: ConcurrentEffect[F]): F[Client[F]] =
       F.delay(Client[F] { req =>
         Resource.liftF(for{
           freq <- toFinagleReq(req)
           resp <- toF(svc(freq))
         }yield resp).map(toHttp4sResp)
        })

  def resource[F[_]](dest: String)(
    implicit F: ConcurrentEffect[F]): Resource[F, Client[F]] = {
    Resource.make(F.delay(Http.newService(dest))){svc => toF(svc.close())}
    .flatMap(svc => Resource.liftF(allocate(svc)))
  }
  def toFinagleReq[F[_]](req: Request[F])(implicit F: ConcurrentEffect[F]):F[Req] = {
    val method = Method(req.method.name)
    val finagleReq = RequestBuilder()
      .url(req.uri.toString())
      .addHeaders(req.headers.toList.map(h => (h.name.toString, h.value)).toMap)
      .build(method,Some(Buf.Empty))
    req.body.mapChunks{bytes =>
      finagleReq.write(bytes.toArray)
      bytes
    }
      .compile.drain.map(_ => finagleReq)
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
