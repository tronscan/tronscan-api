package org.tronscan.service

import java.net.InetAddress

import com.maxmind.geoip2.DatabaseReader
import javax.inject.{Inject, Singleton}
import play.api.Environment
import org.tronscan.models.{IPGeoModel, IPGeoModelRepository}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.{Failure, Success, Try}

@Singleton
class GeoIPService @Inject() (
  env: Environment,
  iPGeoModelRepository: IPGeoModelRepository) {

  lazy val reader = {
    val stream = env.resourceAsStream("GeoLite2-City.mmdb").get
    new DatabaseReader.Builder(stream).build
  }

  def queryForIp(ipAddress: String): Future[IPGeoModel] = {
    val inetAddress = InetAddress.getByName(ipAddress)

    Try(reader.city(inetAddress)) match {
      case Success(response) =>

        val country = Option(response.getCountry).map(_.getName).getOrElse("-")
        val city = Option(response.getCity).map(_.getName).getOrElse("-")
        val location = Option(response.getLocation)

        val geo = IPGeoModel(
          ip = ipAddress,
          country = Option(country).getOrElse(""),
          city = Option(city).getOrElse(""),
          lat = location.map(_.getLatitude).map(_.toDouble).getOrElse(0D),
          lng = location.map(_.getLongitude).map(_.toDouble).getOrElse(0D),
        )

        for {
          _ <- iPGeoModelRepository.insertAsync(geo)
        } yield geo

      case Failure(exc) =>
        Future.successful(IPGeoModel(
          ip = ipAddress,
          country = "",
          city = "",
          lat = 0,
          lng = 0))
    }

  }

  def findForIp(ip: String) = {
    iPGeoModelRepository.findByIp(ip).flatMap {
      case Some(ipGeo) =>
        Future.successful(ipGeo)
      case None =>
        queryForIp(ip)
    }
  }

}
