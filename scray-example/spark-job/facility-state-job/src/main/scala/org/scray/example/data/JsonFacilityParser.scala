package org.scray.example.data

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import com.typesafe.scalalogging.LazyLogging

class JsonFacilityParser extends LazyLogging {
  val mapper = new ObjectMapper()
  mapper.registerModule(DefaultScalaModule)
  mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

  def parse(json: String): Option[Facility[Long]] = {
    try {
      // Return first element only! Arry schould contain one element
      return Some(mapper.readValue(json, classOf[Array[Facility[Long]]]).toSeq.head)
    } catch {
      case e: Throwable => {
        println("Error")
        logger.error(s"Exception while parsing facility element ${json}. ${e.getMessage}")
        return None
      }
    }
    return None
  }
}