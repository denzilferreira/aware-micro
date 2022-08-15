package com.awareframework.micro

import org.apache.commons.lang.StringEscapeUtils
import io.vertx.config.ConfigRetriever
import io.vertx.config.ConfigRetrieverOptions
import io.vertx.config.ConfigStoreOptions
import io.vertx.core.AbstractVerticle
import io.vertx.core.Future
import io.vertx.core.Promise
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.core.net.PemKeyCertOptions
import io.vertx.core.net.PemTrustOptions
import io.vertx.pgclient.PgConnectOptions
import io.vertx.pgclient.PgPool
import io.vertx.pgclient.SslMode
import io.vertx.sqlclient.PoolOptions
import io.vertx.sqlclient.SqlClient
import java.util.stream.Collectors
import java.util.stream.StreamSupport

class PostgresVerticle : AbstractVerticle() {

  private lateinit var parameters: JsonObject
  private lateinit var sqlClient: PgPool

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

        // https://vertx.io/docs/4.3.3/apidocs/io/vertx/pgclient/PgConnectOptions.html
        val connectOptions = PgConnectOptions()
          .setHost(serverConfig.getString("database_host"))
          .setPort(serverConfig.getInteger("database_port"))
          .setDatabase(serverConfig.getString("database_name"))
          .setUser(serverConfig.getString("database_user"))
          .setPassword(serverConfig.getString("database_pwd"))
        setDatabaseSslMode(serverConfig, connectOptions)

        val poolOptions = PoolOptions().setMaxSize(5)

        // Create the client pool
        sqlClient = PgPool.pool(vertx, connectOptions, poolOptions)

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
          // https://access.redhat.com/documentation/ja-jp/red_hat_build_of_eclipse_vert.x/4.0/html/eclipse_vert.x_4.0_migration_guide/changes-in-handlers_changes-in-common-components
          ).onComplete { response ->
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
        // https://access.redhat.com/documentation/ja-jp/red_hat_build_of_eclipse_vert.x/4.0/html/eclipse_vert.x_4.0_migration_guide/changes-in-vertx-jdbc-client_changes-in-client-components#running_queries_on_managed_connections
        connection
          .query("SELECT * FROM $table WHERE device_id = '$device_id' AND timestamp between $start AND $end ORDER BY timestamp ASC")
          .execute()
          .onFailure { e ->
            println("Failed to retrieve data: ${e.message}")
            connection.close()
            dataPromise.fail(e.message)
          }
          .onSuccess { rows ->
            println("$device_id : retrieved ${rows.size()} records from $table")
            connection.close()
            dataPromise.complete(JsonArray(StreamSupport.stream(rows.spliterator(), false)
              .map { row -> row.toJson() }
              .collect(Collectors.toList())))
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

          // https://access.redhat.com/documentation/ja-jp/red_hat_build_of_eclipse_vert.x/4.0/html/eclipse_vert.x_4.0_migration_guide/changes-in-vertx-jdbc-client_changes-in-client-components#running_queries_on_managed_connections
          connection.query(updateItem)
            .execute()
            .onFailure { e ->
              println("Failed to process update: ${e.message}")
              connection.close()
            }
            .onSuccess { _ ->
              println("$device_id updated $table: ${entry.encode()}")
              connection.close()
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
        connection.query(deleteBatch)
          .execute()
          .onFailure { e ->
            println("Failed to process delete batch: ${e.message}")
            connection.close()
          }
          .onSuccess { _ ->
            println("$device_id deleted from $table: ${data.size()} records")
            connection.close()
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
        connect.query("CREATE TABLE IF NOT EXISTS `$table` (`_id` INT UNSIGNED AUTO_INCREMENT PRIMARY KEY, `timestamp` DOUBLE NOT NULL, `device_id` VARCHAR(128) NOT NULL, `data` JSON NOT NULL, INDEX `timestamp_device` (`timestamp`, `device_id`))")
          .execute()
          .onFailure { e ->
            promise.fail(e.message)
            connect.close()
          }
          .onSuccess { _ ->
            promise.complete(true)
            connect.close()
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
    createTable(table)
      .onSuccess { _ ->
        sqlClient.getConnection { connectionResult ->
          if (connectionResult.succeeded()) {
            val connection = connectionResult.result()
            val rows = data.size()
            val values = ArrayList<String>()
            for (i in 0 until data.size()) {
              val entry = data.getJsonObject(i)

              // https://github.com/eclipse-vertx/vert.x/commit/ea0eddb129530ab3719c0ef86b471894876ec519#diff-07f061e092a63da24a06ab4507d15125e3377034f21eee18c6d4261f6714e709L241
              values.add("('$device_id', '${entry.getDouble("timestamp")}', '${StringEscapeUtils.escapeJavaScript(entry.encode())}')")
            }
            val insertBatch =
              "INSERT INTO `$table` (`device_id`,`timestamp`,`data`) VALUES ${values.stream().map(Any::toString).collect(
                Collectors.joining(",")
              )}"
            connection.query(insertBatch)
              .execute()
              .onFailure { e ->
                println("Failed to process batch: ${e.message}")
                connection.close()
              }
              .onSuccess { _ ->
                println("$device_id inserted to $table: $rows records")
                connection.close()
              }
          }
        }
      }
      .onFailure { e ->
        println(e.message)
      }
  }

  override fun stop() {
    super.stop()
    println("AWARE Micro: PostgreSQL client shutdown")
    sqlClient.close()
  }

  private fun setDatabaseSslMode(serverConfig: JsonObject, options: PgConnectOptions) {
    val sslMode = serverConfig.getString("database_ssl_mode")
    when (sslMode) {
      null, "", "disable", "disabled" -> {
        options.setSslMode(SslMode.DISABLE)
      }
      "prefer", "preferred" -> {
        options.setSslMode(SslMode.PREFER)
        options.setPemTrustOptions(PemTrustOptions().addCertPath(serverConfig.getString("database_ssl_path_ca_cert_pem")))
        options.setPemKeyCertOptions(PemKeyCertOptions()
          .setKeyPath(serverConfig.getString("database_ssl_path_client_key_pem"))
          .setCertPath(serverConfig.getString("database_ssl_path_client_cert_pem")))
      }
    }
  }
}
