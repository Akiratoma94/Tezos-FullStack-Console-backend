package io.scalac.tezos.translator

import java.util.concurrent.TimeUnit

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.stream.ActorMaterializer
import com.github.benmanes.caffeine.cache.Caffeine
import com.typesafe.config.ConfigFactory
import io.scalac.tezos.translator.actor.EmailSender
import io.scalac.tezos.translator.config.Configuration
import io.scalac.tezos.translator.model.EmailAddress
import io.scalac.tezos.translator.model.types.Auth
import io.scalac.tezos.translator.repository.{ Emails2SendRepository, LibraryRepository, UserRepository }
import io.scalac.tezos.translator.routes.utils.MMTranslator
import io.scalac.tezos.translator.service.{ Emails2SendService, LibraryService, SendEmailsServiceImpl, UserService }
import scalacache._
import scalacache.caffeine._
import slick.jdbc.PostgresProfile
import slick.jdbc.PostgresProfile.api._

import scala.concurrent.{ ExecutionContextExecutor, Future }
import scala.io.StdIn
import scala.util.{ Failure, Success }

object Boot {

  def main(args: Array[String]): Unit = {
    implicit val system: ActorSystem                        = ActorSystem("tezos-translator")
    implicit val materializer: ActorMaterializer            = ActorMaterializer()
    implicit val executionContext: ExecutionContextExecutor = system.dispatcher

    val log        = system.log
    val config     = ConfigFactory.load().getConfig("console")
    val httpConfig = config.getConfig("http")
    val host       = httpConfig.getString("host")
    val port       = httpConfig.getInt("port")
    val maybeConfiguration =
      Configuration.getConfig match {
        case Right(v) => Success(v)
        case Left(configReaderFailures) =>
          val failuresStr = configReaderFailures.toList.map(_.description).mkString("\n")
          log.error(failuresStr)
          Failure(new Exception("Configuration cannot be loaded. See logs for details."))
      }

    implicit val db: PostgresProfile.backend.Database = Database.forConfig("tezos-db")
    val emails2SendRepo                               = new Emails2SendRepository
    val userRepository                                = new UserRepository
    val email2SendService                             = new Emails2SendService(emails2SendRepo, db)

    val underlyingCaffeineCache =
      Caffeine
        .newBuilder()
        .expireAfterWrite(60, TimeUnit.MINUTES)
        .build[String, Entry[Auth.Username]]

    val tokenToUser: Cache[Auth.Username] = CaffeineCache(underlyingCaffeineCache)
    val userService                       = new UserService(userRepository, tokenToUser, db)

    val bindingFuture =
      for {
        cfg               <- Future.fromTry(maybeConfiguration)
        dbEvolution       = SqlDbEvolution(cfg.dbEvolutionConfig)
        _                 <- if (cfg.dbEvolutionConfig.enabled) dbEvolution.runEvolutions() else Future.successful(0)
        libraryRepo       = new LibraryRepository(cfg.dbUtility, db)
        libraryService    = new LibraryService(libraryRepo, log)
        sendEmailsService <- Future.fromTry(SendEmailsServiceImpl(email2SendService, log, cfg.email, cfg.cron))
        cronEmailSender   = EmailSender(sendEmailsService, cfg.cron)
        adminEmail        <- Future.fromTry(EmailAddress.fromString(cfg.email.receiver))
        routes            = new Routes(email2SendService, libraryService, userService, MMTranslator, log, cfg.reCaptcha, adminEmail)
        binding           <- Http().bindAndHandle(routes.allRoutes, host, port)
        _                 <- Future.successful(StdIn.readLine())
      } yield (cronEmailSender, binding)

    log.info(s"Server online at http://$host:$port\nPress RETURN to stop...")

    bindingFuture
      .transformWith {
        case Success((cronEmailSender, binding)) =>
          cronEmailSender.cancel()
          binding.unbind()
        case Failure(ex) =>
          Future(log.error(ex, s"An error has occurred!"))
      }
      .onComplete(_ => system.terminate())
  }
}
