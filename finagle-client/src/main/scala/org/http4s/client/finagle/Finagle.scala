package org.http4s
package client
package finagle

import cats.effect._
import cats.syntax.functor._
import com.twitter.finagle.{Http,Service}
import com.twitter.finagle.http.{Request => Req, Response=>Resp, Method, RequestBuilder}
import com.twitter.util.{Return, Throw, Future}
import com.twitter.io._
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
    val reqheaders = req.headers.toList.map(h => (h.name.toString, h.value)).toMap
    val reqBuilder = RequestBuilder().url(req.uri.toString)
    .addHeaders(reqheaders)
    (method, req.headers) match {
      case (Method.Get, _) => F.delay(reqBuilder.buildGet)
      case (Method.Post, _) if reqheaders.get("Content-Type").map{v =>
        println("==-------")
        println(v)
        v.take(19) == ("multipart/form-data")}.getOrElse(false) =>
        println("===================")
                req.as[Array[Byte]].map{_=>
          val r = reqBuilder
            .addFormElement(("text","This is text."))
            .buildFormPost(true)
                  r
                }
      case (Method.Post, _) =>
        req.as[Array[Byte]].map{b=>
          val r = reqBuilder.buildPost(Buf.ByteArray.Owned(b))
          r.setChunked(req.isChunked)
          r
        }
      case (m, _) =>  F.delay(reqBuilder.build(m, None))
    }
  }

  def toHttp4sResp[F[_]](resp: Resp): Response[F] = {
    def toStream(buf: Buf): Stream[F, Byte] = {
      Stream.chunk[F, Byte](Chunk.array(Buf.ByteArray.Owned.extract(buf)))
    }
    println(s"--${resp.contentString}----${resp.version}---${resp.headerMap}---${resp.isChunked}-----")
    Response[F](
      status = Status(resp.status.code)
    ).withEntity(toStream(resp.content))
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
