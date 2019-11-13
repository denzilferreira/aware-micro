package com.awareframework.micro

import io.vertx.config.ConfigRetriever
import io.vertx.config.ConfigRetrieverOptions
import io.vertx.config.ConfigStoreOptions
import io.vertx.core.AbstractVerticle
import io.vertx.core.Promise
import io.vertx.core.impl.StringEscapeUtils
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import org.influxdb.InfluxDB
import org.influxdb.InfluxDBFactory
import org.influxdb.BatchOptions
import java.util.concurrent.TimeUnit
import org.influxdb.dto.Point
import java.util.stream.Collectors

class InfluxDbVerticle : AbstractVerticle() {

  private lateinit var parameters: JsonObject
  private lateinit var influxDB: InfluxDB

  override fun start(startPromise: Promise<Void>?) {
    super.start(startPromise)


    val configStore = ConfigStoreOptions()
      .setType("file")
      .setFormat("json")
      .setConfig(JsonObject()
      .put("path", "aware-config.json"))

    val configRetrieverOptions = ConfigRetrieverOptions()
      .addStore(configStore)
      .setScanPeriod(5000)

    val eventBus = vertx.eventBus()

    val configReader = ConfigRetriever.create(vertx, configRetrieverOptions)
    configReader.getConfig { config ->
      if (config.succeeded() && config.result().containsKey("server")) {
        parameters = config.result()
        val serverConfig = parameters.getJsonObject("server")
        val host = serverConfig.getString("database_host")
        val port = serverConfig.getInteger("database_port")

        influxDB = InfluxDBFactory.connect(
          "$host:$port"
        );

        println("Connected to InfluxDB")

        influxDB.createDatabase(serverConfig.getString("database_name"))
        influxDB.setDatabase(serverConfig.getString("database_name"))

        influxDB.enableBatch(BatchOptions.DEFAULTS);

        eventBus.consumer<String>("createTable") { receivedMessage ->
          createTable(receivedMessage.body())
        }

        eventBus.consumer<JsonObject>("insertData") { receivedMessage ->
          val postData = receivedMessage.body()
          insertData(
            device_id = postData.getString("device_id"),
            table = postData.getString("table"),
            data = JsonArray(postData.getString("data"))
          )
        }
      }
    }
  }

  fun createTable(table: String) {
    println("Does nothing")
  }

  fun insertData(device_id: String, table: String, data: JsonArray) {
    val rows = data.size()
    for (i in 0 until data.size()) {
      val entry = data.getJsonObject(i)

      var point = Point.measurement(table).time(entry.getLong("timestamp"), TimeUnit.SECONDS)

      point.tag("device_id", device_id)
      point.addField("device_id", device_id)

      entry.forEach { (key, value) -> println("$key $value") }

      influxDB.write(point.build());
    }
  }

  override fun stop() {
    super.stop()
    println("AWARE Micro: influxDB client shutdown")
    influxDB.close()
  }
}
