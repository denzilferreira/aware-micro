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
import org.influxdb.dto.BatchPoints
import org.influxdb.dto.Query
import org.influxdb.dto.QueryResult
import java.util.stream.Collectors

class InfluxDbVerticle : AbstractVerticle() {

  private lateinit var parameters: JsonObject
  private lateinit var influxDB: InfluxDB
  private lateinit var batchPoints: BatchPoints
  private lateinit var query: Query
  private lateinit var queryResults: QueryResult
  private lateinit var device_label: String


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
        val user = serverConfig.getString("database_user")
        val password = serverConfig.getString("database_pwd")

        influxDB = InfluxDBFactory.connect(
          "$host:$port",
          user,
          password
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
    val integerList = arrayOf(
      "call_duration", 
      "double_altitude",
      "accuracy",
      "is_silent",
      "bt_rssi",
      "call_type",
      "screen_status"
      )

    val rows = data.size()

    println("Processing insert data")

    // Query for device_label by device_id
    query = Query("SELECT label FROM aware_device WHERE device_id = '$device_id'", "awaredb")
    queryResults = influxDB.query(query)
    println(queryResults.getResults().get(0).getSeries())
    if(queryResults.getResults().get(0).getSeries() != null) {
      device_label = queryResults.getResults().get(0).getSeries().get(0).getValues().get(0).get(1).toString()
    }


    batchPoints = BatchPoints.database("awaredb").build()

    for (i in 0 until data.size()) {
      val entry = data.getJsonObject(i)

      var point = Point.measurement(table)
                         .time(entry.getLong("timestamp"), TimeUnit.MILLISECONDS)
                         .tag("device_id", device_id)

      if(queryResults.getResults().get(0).getSeries() != null) {

        point.tag("device_label", device_label)
      } 

      entry.forEach { (key, value) ->

        if( table === "locations_visit" && key === "name") {
          println("Not storing location name");
        } else {
          when (value) {
            is String -> {
              point.addField(key, value)
            }
            is Int -> {
              if(key in integerList){  
                  point.addField(key + "_integer", value)
                } else {
                  point.addField(key, value)
                }
            }
            is Double -> {
              point.addField(key, value)
            }
            is Long -> {
              point.addField(key, value)
            }
            is Float -> {
              if(key === "double_decibels") {
                  point.addField(key + "_float", value)
                } else {
                  point.addField(key, value)
                }
            }
            else -> println("Unknown Type")
          }
        }
      }

      batchPoints.point(point.build());
    }


    influxDB.write(batchPoints)
  }

  override fun stop() {
    super.stop()
    println("AWARE Micro: influxDB client shutdown")
    influxDB.close()
  }
}
