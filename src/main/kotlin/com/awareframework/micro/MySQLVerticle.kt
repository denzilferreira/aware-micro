package com.awareframework.micro

import io.vertx.config.ConfigRetriever
import io.vertx.config.ConfigRetrieverOptions
import io.vertx.config.ConfigStoreOptions
import io.vertx.core.AbstractVerticle
import io.vertx.core.Promise
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.asyncsql.MySQLClient
import io.vertx.ext.sql.SQLClient

class MySQLVerticle : AbstractVerticle() {

  private lateinit var parameters: JsonObject
  private lateinit var sqlClient: SQLClient

  override fun start(startPromise: Promise<Void>?) {
    super.start(startPromise)
    println("AWARE Micro: MySQL connector initiated...")

    val configStore = ConfigStoreOptions()
      .setType("file")
      .setFormat("json")
      .setConfig(JsonObject().put("path", "aware-config.json"))

    val configRetrieverOptions = ConfigRetrieverOptions()
      .addStore(configStore)
      .setScanPeriod(5000)

    val configReader = ConfigRetriever.create(vertx, configRetrieverOptions)
    configReader.getConfig { config ->
      if (config.succeeded() && config.result().containsKey("server")) {
        parameters = config.result()
        val serverConfig = parameters.getJsonObject("server")
        println("Server config from MySQL ${serverConfig.encodePrettily()}")

        val mysqlConfig = JsonObject()
          .put("host", serverConfig.getString("database_host"))
          .put("port", serverConfig.getInteger("database_port"))
          .put("database", serverConfig.getString("database_name"))
          .put("username", serverConfig.getString("database_user"))
          .put("password", serverConfig.getString("database_pwd"))
          .put("sslMode", "prefer")
          .put("sslRootCert", serverConfig.getString("path_fullchain_pem"))

        sqlClient = MySQLClient.createShared(vertx, mysqlConfig)

        sqlClient.getConnection { result ->
          if (result.succeeded()) {
            val connection = result.result()
            val eventBus = vertx.eventBus()

            eventBus.consumer<String>("createTable") { receivedMessage ->
              println("Received createTable: ${receivedMessage.body()}")
              connection.query("CREATE TABLE IF NOT EXISTS `${receivedMessage.body()}` (`_id` INT UNSIGNED AUTO_INCREMENT PRIMARY KEY, `timestamp` DOUBLE NOT NULL, `device_id` VARCHAR(128) NOT NULL, `data` JSON NOT NULL)") {
                if (result.failed()) {
                  println("Failed to create table ${receivedMessage.body()}: ${result.cause().message}")
                }
              }
            }

            eventBus.consumer<JsonObject>("insertData") { receivedMessage ->
              println("Received insertTable: ${receivedMessage.body()}")
              val postData = JsonObject(receivedMessage.body().toString())
              val dataArray = JsonArray(postData.getString("data"))
              for(i in 0..dataArray.size()) {
                val data = dataArray.getJsonObject(i)
                connection.query("INSERT INTO `${postData.getString("table")}`(`device_id`, `timestamp`, `data`) VALUES (`${postData.getString("device_id")}`, `${System.currentTimeMillis()}`, `${data.encode()}`)") {
                  if (it.failed()) {
                    println("Failed to insert $postData ${data.encode()}")
                  }
                }
              }
            }

            connection.close()
          } else {
            println("Failed to connect to MySQL server: ${result.cause().message}")
          }
        }
      }
    }
  }
}
