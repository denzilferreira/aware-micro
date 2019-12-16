package com.awareframework.micro

import io.vertx.config.ConfigRetriever
import io.vertx.config.ConfigRetrieverOptions
import io.vertx.config.ConfigStoreOptions
import io.vertx.core.AbstractVerticle
import io.vertx.core.Future
import io.vertx.core.Promise
import io.vertx.core.impl.StringEscapeUtils
import io.vertx.core.json.Json
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.asyncsql.MySQLClient
import io.vertx.ext.sql.SQLClient
import java.util.stream.Collectors

class MySQLVerticle : AbstractVerticle() {

  private lateinit var parameters: JsonObject
  private lateinit var sqlClient: SQLClient

  override fun start(startPromise: Promise<Void>?) {
    super.start(startPromise)

    val configStore = ConfigStoreOptions()
      .setType("file")
      .setFormat("json")
      .setConfig(JsonObject().put("path", "aware-config.json"))

    val configRetrieverOptions = ConfigRetrieverOptions()
      .addStore(configStore)
      .setScanPeriod(5000)

    val eventBus = vertx.eventBus()

    val configReader = ConfigRetriever.create(vertx, configRetrieverOptions)
    configReader.getConfig { config ->
      if (config.succeeded() && config.result().containsKey("server")) {
        parameters = config.result()
        val serverConfig = parameters.getJsonObject("server")

        val mysqlConfig = JsonObject()
          .put("host", serverConfig.getString("database_host"))
          .put("port", serverConfig.getInteger("database_port"))
          .put("database", serverConfig.getString("database_name"))
          .put("username", serverConfig.getString("database_user"))
          .put("password", serverConfig.getString("database_pwd"))
          .put("sslMode", "prefer")
          .put("sslRootCert", serverConfig.getString("path_fullchain_pem"))

        sqlClient = MySQLClient.createShared(vertx, mysqlConfig)

        eventBus.consumer<JsonObject>("insertData") { receivedMessage ->
          val postData = receivedMessage.body()
          insertData(
            device_id = postData.getString("device_id"),
            table = postData.getString("table"),
            data = JsonArray(postData.getString("data"))
          )
        }

        eventBus.consumer<JsonObject>("updateData") { receivedMessage ->
          val postData = receivedMessage.body()
          updateData(
            device_id = postData.getString("device_id"),
            table = postData.getString("table"),
            data = JsonArray(postData.getString("data"))
          )
        }

        eventBus.consumer<JsonObject>("deleteData") { receivedMessage ->
          val postData = receivedMessage.body()
          deleteData(
            device_id = postData.getString("device_id"),
            table = postData.getString("table"),
            data = JsonArray(postData.getString("data"))
          )
        }

        eventBus.consumer<JsonObject>("getData") { receivedMessage ->
          val postData = receivedMessage.body()
          getData(
            device_id = postData.getString("device_id"),
            table = postData.getString("table"),
            start = postData.getDouble("start"),
            end = postData.getDouble("end")
          ).setHandler { response ->
            receivedMessage.reply(response.result())
          }
        }
      }
    }
  }

  //Fetch data from the database and return results as JsonArray
  fun getData(device_id: String, table: String, start: Double, end: Double): Future<JsonArray> {

    val dataPromise: Promise<JsonArray> = Promise.promise()

    sqlClient.getConnection { connectionResult ->
      if (connectionResult.succeeded()) {
        val connection = connectionResult.result()
        connection.query("SELECT * FROM $table WHERE device_id = '$device_id' AND timestamp between $start AND $end ORDER BY timestamp ASC") { result ->
          if (result.failed()) {
            println("Failed to retrieve data: ${result.cause().message}")
            connection.close()
            dataPromise.fail(result.cause().message)
          } else {
            println("$device_id : retrieved ${result.result().numRows} records from $table")
            connection.close()
            dataPromise.complete(result.result().output)
          }
        }
      }
    }

    return dataPromise.future()
  }

  fun updateData(device_id: String, table: String, data: JsonArray) {
    sqlClient.getConnection { connectionResult ->
      if (connectionResult.succeeded()) {
        val connection = connectionResult.result()
        for (i in 0 until data.size()) {
          val entry = data.getJsonObject(i)
          val updateItem =
            "UPDATE '$table' SET data = $entry WHERE device_id = '$device_id' AND timestamp = ${entry.getDouble("timestamp")}"

          connection.query(updateItem) { result ->
            if (result.failed()) {
              println("Failed to process update: ${result.cause().message}")
              connection.close()
            } else {
              println("$device_id updated $table: ${entry.encode()}")
              connection.close()
            }
          }
        }
      } else {
        println("Failed to establish connection: ${connectionResult.cause().message}")
      }
    }
  }

  fun deleteData(device_id: String, table: String, data: JsonArray) {
    sqlClient.getConnection { connectionResult ->
      if (connectionResult.succeeded()) {
        val connection = connectionResult.result()
        val timestamps = mutableListOf<Double>()
        for (i in 0 until data.size()) {
          val entry = data.getJsonObject(i)
          timestamps.plus(entry.getDouble("timestamp"))
        }

        val deleteBatch =
          "DELETE from '$table' WHERE device_id = '$device_id' AND timestamp in (${timestamps.stream().map(Any::toString).collect(
            Collectors.joining(",")
          )})"
        connection.query(deleteBatch) { result ->
          if (result.failed()) {
            println("Failed to process delete batch: ${result.cause().message}")
            connection.close()
          } else {
            println("$device_id deleted from $table: ${data.size()} records")
            connection.close()
          }
        }
      } else {
        println("Failed to establish connection: ${connectionResult.cause().message}")
      }
    }
  }

  /**
   * Create a database table if it doesn't exist
   */
  fun createTable(table: String): Future<Boolean> {
    val promise = Promise.promise<Boolean>()
    sqlClient.getConnection { connectionResult ->
      if (connectionResult.succeeded()) {
        val connect = connectionResult.result()
        connect.query("CREATE TABLE IF NOT EXISTS `$table` (`_id` INT UNSIGNED AUTO_INCREMENT PRIMARY KEY, `timestamp` DOUBLE NOT NULL, `device_id` VARCHAR(128) NOT NULL, `data` JSON NOT NULL, INDEX `timestamp_device` (`timestamp`, `device_id`))") { createResult ->
          if (createResult.failed()) {
            promise.fail(createResult.cause().message)
            connect.close()
          } else {
            promise.complete(true)
            connect.close()
          }
        }
      } else {
        promise.fail(connectionResult.cause().message)
      }
    }
    return promise.future()
  }

  /**
   * Insert batch of data into database table
   */
  fun insertData(table: String, device_id: String, data: JsonArray) {
    createTable(table).setHandler { creation ->
      if (creation.succeeded()) {
        sqlClient.getConnection { connectionResult ->
          if (connectionResult.succeeded()) {
            val connection = connectionResult.result()
            val rows = data.size()
            val values = ArrayList<String>()
            for (i in 0 until data.size()) {
              val entry = data.getJsonObject(i)
              values.add("('$device_id', '${entry.getDouble("timestamp")}', '${StringEscapeUtils.escapeJavaScript(entry.encode())}')")
            }
            val insertBatch =
              "INSERT INTO `$table` (`device_id`,`timestamp`,`data`) VALUES ${values.stream().map(Any::toString).collect(
                Collectors.joining(",")
              )}"
            connection.query(insertBatch) { result ->
              if (result.failed()) {
                println("Failed to process batch: ${result.cause().message}")
                connection.close()
              } else {
                println("$device_id inserted to $table: $rows records")
                connection.close()
              }
            }
          }
        }
      } else {
        println(creation.cause().message)
      }
    }
  }

  override fun stop() {
    super.stop()
    println("AWARE Micro: MySQL client shutdown")
    sqlClient.close()
  }
}
