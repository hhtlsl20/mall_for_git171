package com.hht.recommender

import com.mongodb.casbah.commons.MongoDBObject
import com.mongodb.casbah.{MongoClient, MongoClientURI}
import org.apache.spark.SparkConf
import org.apache.spark.sql.{DataFrame, SparkSession}


case class Product( productId: Int, name: String, imageUrl: String, categories: String, tags: String )

/**
  * Rating数据集
  * 4867        用户ID
  * 457976      商品ID
  * 5.0         评分
  * 1395676800  时间戳
  */
case class Rating( userId: Int, productId: Int, score: Double, timestamp: Int )

/**
  * MongoDB连接配置
  * @param uri    MongoDB的连接uri
  * @param db     要操作的db
  */
case class MongoConfig( uri: String, db: String )

object DataLoader {
  // 定义数据文件路径
  val PRODUCT_DATA_PATH = "D:\\Desktop\\ECommerceRecommendSystem\\recommender\\DataLoader\\src\\main\\resources\\products.csv"
  val RATING_DATA_PATH = "D:\\Desktop\\ECommerceRecommendSystem\\recommender\\DataLoader\\src\\main\\resources\\ratings.csv"
  // 定义mongodb中存储的表名
  val MONGODB_PRODUCT_COLLECTION = "Product"
  val MONGODB_RATING_COLLECTION = "Rating"

  def main(args: Array[String]): Unit = {
    val config = Map(
      "spark.cores" -> "local[*]",
      "mongo.uri" -> "mongodb://8.129.42.178:27017/recommender",
      "mongo.db" -> "recommender"
    )
    // 创建一个spark config
    val sparkConf = new SparkConf().setMaster(config("spark.cores")).setAppName("DataLoader")
    // 创建spark session
    val spark = SparkSession.builder().config(sparkConf).getOrCreate()

    import spark.implicits._

    // 加载数据
    val productRDD = spark.sparkContext.textFile(PRODUCT_DATA_PATH)
    val productDF = productRDD.map( item => {
      // product数据通过^分隔，切分出来
      val attr = item.split("\\^")
      // 转换成Product
      Product( attr(0).toInt, attr(1).trim, attr(4).trim, attr(5).trim, attr(6).trim )
    } ).toDF()

    val ratingRDD = spark.sparkContext.textFile(RATING_DATA_PATH)
    val ratingDF = ratingRDD.map( item => {
      val attr = item.split(",")
      Rating( attr(0).toInt, attr(1).toInt, attr(2).toDouble, attr(3).toInt )
    } ).toDF()

    implicit val mongoConfig = MongoConfig( config("mongo.uri"), config("mongo.db") )
    storeDataInMongoDB( productDF, ratingDF )

    spark.stop()
  }
  def storeDataInMongoDB( productDF: DataFrame, ratingDF: DataFrame )(implicit mongoConfig: MongoConfig): Unit ={
    // 新建一个mongodb的连接，客户端
    val mongoClient = MongoClient( MongoClientURI(mongoConfig.uri) )
    // 定义要操作的mongodb表，可以理解为 db.Product
    val productCollection = mongoClient( mongoConfig.db )( MONGODB_PRODUCT_COLLECTION )
    val ratingCollection = mongoClient( mongoConfig.db )( MONGODB_RATING_COLLECTION )

    // 如果表已经存在，则删掉
    productCollection.dropCollection()
    ratingCollection.dropCollection()

    // 将当前数据存入对应的表中
    productDF.write
      .option("uri", mongoConfig.uri)
      .option("collection", MONGODB_PRODUCT_COLLECTION)
      .mode("overwrite")
      .format("com.mongodb.spark.sql")
      .save()

    ratingDF.write
      .option("uri", mongoConfig.uri)
      .option("collection", MONGODB_RATING_COLLECTION)
      .mode("overwrite")
      .format("com.mongodb.spark.sql")
      .save()

    // 对表创建索引
    productCollection.createIndex( MongoDBObject( "productId" -> 1 ) )
    ratingCollection.createIndex( MongoDBObject( "productId" -> 1 ) )
    ratingCollection.createIndex( MongoDBObject( "userId" -> 1 ) )

    mongoClient.close()
  }
}
