package org.http4s
package client
package finagle

import cats.effect.IO

class FinagleSpec extends ClientRouteTestBattery("FinagleClient") {
  override def clientResource = Finagle.resource[IO]("localhost:8080")
  override def clientResourceGen = Finagle.resource[IO]
}
