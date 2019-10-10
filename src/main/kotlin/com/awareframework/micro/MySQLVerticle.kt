package com.awareframework.micro

import io.vertx.core.AbstractVerticle
import io.vertx.core.Promise

class MySQLVerticle : AbstractVerticle() {

  override fun start(startPromise: Promise<Void>?) {
    super.start(startPromise)
    println("Starting MySQL connector")


  }
}
