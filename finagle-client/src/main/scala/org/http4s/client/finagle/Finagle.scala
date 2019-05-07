package org.http4s
package client
package finagle

import cats.effect._
import cats.syntax.functor._
import com.twitter.finagle.{Http,Service}
import com.twitter.finagle.http.{Request => Req, Response=>Resp, Method, RequestBuilder}
import com.twitter.util.{Return, Throw, Future}
import com.twitter.io.Buf
import cats.syntax.flatMap._
import fs2.{
  Chunk, Stream
}

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
    Resource.make(F.delay(Http.newService(dest))){_ => F.delay(())}
    .flatMap(svc => Resource.liftF(allocate(svc)))
  }
  def toFinagleReq[F[_]](req: Request[F])(implicit F: ConcurrentEffect[F]):F[Req] = {
    val method = Method(req.method.name)
    val headers = req.headers.toList.map(h => (h.name.toString, h.value)).toMap
    val finagleReq = RequestBuilder()
      .url(req.uri.toString())
      .addHeaders(headers)
      .build(method,None)

    req.body.mapChunks{bytes =>
      finagleReq.write(bytes.toArray)
      bytes
    }
      .compile.drain.map{
        _ =>
        finagleReq
      }
  }

  def toHttp4sResp[F[_]](resp: Resp): Response[F] = {
    Response[F](
      status = Status(resp.status.code)
    ).withEntity(Stream.chunk[F, Byte](Chunk.array(Buf.ByteArray.Owned.extract(resp.content))))
      .withHeaders(Headers(resp.headerMap.toList.map{case (name, value) => Header(name, value)}))
  }

  def toF[F[_], A](f: Future[A])(implicit F: Async[F]): F[A] = F.async{cb=>
    f.respond{
      case Return(value) => cb(Right(value))
      case Throw(exception) => cb(Left(exception))
    }
    ()
  }
}
