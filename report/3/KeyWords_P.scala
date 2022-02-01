import org.apache.spark
import org.apache.spark.ml.Pipeline
import org.apache.spark.sql.{Dataset, Row, SparkSession}
import org.apache.spark.ml.feature.{CountVectorizer, CountVectorizerModel, HashingTF, IDF, RegexTokenizer}
import org.apache.spark.sql.functions._
import org.apache.spark.mllib.linalg.distributed.{BlockMatrix, IndexedRow, IndexedRowMatrix, RowMatrix}
import org.apache.spark.sql.expressions.Window
import org.apache.spark.sql.types._
import org.apache.spark.sql.types.IntegerType
import org.apache.spark.mllib.linalg.{Vectors => OldVectors}
import org.apache.spark.ml.linalg.{Vectors => NewVectors}
import org.apache.spark.rdd.RDD
import org.apache.commons.lang3.StringUtils

import scala.collection.mutable

// Program takes 1 parameter as argument (K), the number of top K most similar members

// Same as KeyWords_P except we group by parliament party

object KeyWords_P {


  def main(args: Array[String]): Unit = {

    // Create a new spark session
    val ss = SparkSession.builder().appName("lda").master("local[*]").getOrCreate()
    ss.sparkContext.setLogLevel("ERROR")
    import ss.implicits._

    val df = ss.read.option("header", true)
      .csv("file:///home/ozzy/Desktop/bd/dtst.csv")
      .select("political_party", "sitting_date", "speech")
      .na
      .drop

    val check = udf((colValue: String) => {StringUtils.stripAccents(colValue)})
	val df15 = df.select($"political_party", $"sitting_date", check($"speech").as("speech"))
	
    // keep rows of a specific year
    import org.apache.spark.sql.functions.{to_date, to_timestamp}

    val df_date = df15.withColumn("date_y", to_date($"sitting_date", "dd/MM/yyyy")).drop("sitting_date")
    
   
    //df_date.printSchema

    val year = args(0).toInt

    val speechesDF = df_date.where(s"year(date_y) == ${year}").groupBy("political_party")
      .agg(concat_ws(",", collect_list("speech")).as("speeches"))

    val cleanSpeechesDF = speechesDF.withColumn("speechesClean", regexp_replace($"speeches", "[\\_,\\*,\\$,\\#,\\@,\\&]", ""))

    //cleanSpeechesDF.show

    import org.apache.spark.ml.feature.RegexTokenizer

    val speechesDF_tok = new RegexTokenizer().setInputCol("speechesClean")
      .setOutputCol("speechesTok")
      .setMinTokenLength(4)
      .setToLowercase(true)
      .setPattern("[\\s.,!-~'…\"’΄;*^%$@«?|»{}()&–<>/+_ ]")
      .transform(cleanSpeechesDF)

   // speechesDF_tok.show

    val stopwordsPath : String = "file:///home/ozzy/Desktop/bd/avoulos-main/aux_files/new_stop.txt"

    val stopwords = ss.sparkContext.textFile(stopwordsPath)
      .collect
      .toSet


    val filter_stopwords_udf = udf ( (v : scala.collection.mutable.WrappedArray[String]) => v.filterNot(w => stopwords contains w) )

    val speechesFilteredDF = speechesDF_tok.withColumn("speechesTok1", filter_stopwords_udf(speechesDF_tok("speechesTok")))

   // speechesFilteredDF.show

    import org.apache.spark.ml.feature.{CountVectorizerModel, CountVectorizer}

    val cvModel : CountVectorizerModel = new CountVectorizer().setInputCol("speechesTok1")
      .setOutputCol("features")
      .setMaxDF(10)
      .setVocabSize(100000)
      .fit(speechesFilteredDF)
    //.setMinTF(2)

    val cvDF = cvModel.transform(speechesFilteredDF).drop("speeches", "speechesClean", "speechesTok")

   /* import org.apache.spark.ml.feature.{IDF}

    val idf = new IDF().setInputCol("rawFeatures").setOutputCol("features")
    val idfModel = idf.fit(cvDF1)

    val cvDF = idfModel.transform(cvDF1) */

    //cvDF.show

    import org.apache.spark.ml.linalg.Vector
    import org.apache.spark.rdd.RDD

    val n_most_freq = 20

    val zippedVoc = cvModel.vocabulary.zipWithIndex

    val mostFreq_rdd : RDD[Array[String]]  = cvDF.select("features")
      .rdd
      .map(_.getAs[Vector](0))
      .map(_.toSparse)
      .map{ row =>
        row.indices.zip(row.values)
          .sortBy(_._2).take(n_most_freq).map(_._1) }
      .map(arr => {

        zippedVoc.map { case (word, idx) =>
          if (arr.contains(idx))
            word.toString
        }
      }
        .filter(_.!=()))
      .map(arr => arr.map(_.toString))


    mostFreq_rdd.take(5)

    import org.apache.spark.sql.expressions.Window

    val parties = speechesDF.select("political_party").rdd.map(w => w.toString.replaceAll("[\\[\\]]","").capitalize).toDF("name").withColumn("id", row_number().over(Window.orderBy("name"))).cache()

    val df2 = mostFreq_rdd.toDF(s"Most_Frequent_${year}")

    df2.show(30)

    val mostFreqDF = df2.withColumn("id", row_number().over(Window.orderBy(s"Most_Frequent_${year}")))

   // mostFreqDF.show

   // parties.show

    val finalDF = parties.join(mostFreqDF, "id").drop("id")

   // finalDF.show(10, false)

    import scala.collection.mutable.WrappedArray


    finalDF.rdd.
      map { r : org.apache.spark.sql.Row =>
        (r.getAs[String](0), s"(${year},(" + (
          r.getAs[WrappedArray[String]](1).mkString(",").toString) + ")")
      }.saveAsTextFile(s"file:///home/ozzy/Desktop/bd/Erwthma3/results_party_${year}")


    /*

    finalDF.rdd.
        map { r : org.apache.spark.sql.Row =>
            ((r.getAs[String](0), Array((year : Int, Array(
                r.getAs[WrappedArray[String]](1).toArray.mkString(","))))))}.take(1)//.saveAsTextFile("test3")
    */

    //val x = sc.textFile("results_2015/part-00000").map(x => x.split(",")).map(x => x(1)).collect




    ss.stop()
  }
}
