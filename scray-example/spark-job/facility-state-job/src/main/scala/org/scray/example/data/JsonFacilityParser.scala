package de.s_node.data.db

import scala.io.Source._

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import com.twitter.util.Future

import java.nio.file.Paths
import akka.NotUsed
import java.util.concurrent.LinkedBlockingQueue

import scray.example.input.db.fasta.model.Facility
import com.typesafe.scalalogging.slf4j.LazyLogging


class JsonFacilityParser extends LazyLogging {
  val mapper = new ObjectMapper()
  mapper.registerModule(DefaultScalaModule)

  def jsonReader(json: String): Option[Seq[Facility]] = {
    try {
      return Some(mapper.readValue(json, classOf[Array[Facility]]).toSeq)
    } catch {
      case e: Throwable => {
        logger.error(s"Exception while parsing facility element ${json}. ${e.getMessage}")
        return None
      }
    }
    return None
  }
}