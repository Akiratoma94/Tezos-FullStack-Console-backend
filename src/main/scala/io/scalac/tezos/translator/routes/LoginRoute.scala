package io.scalac.tezos.translator.routes

import akka.actor.ActorSystem
import akka.http.scaladsl.model.{HttpResponse, StatusCodes}
import akka.http.scaladsl.server.{Directive, Route}
import io.scalac.tezos.translator.model.UserCredentialsDTO
import io.scalac.tezos.translator.routes.directives.DTOValidationDirective._
import io.scalac.tezos.translator.service.UserService

import scala.util.Success

class LoginRoute(userService: UserService)(implicit as: ActorSystem) extends HttpRoutes with JsonHelper {

  override def routes: Route =
    (pathPrefix("login") & pathEndOrSingleSlash & validateCredentialsFormat & post) { credentials =>
      onComplete(userService.authenticateAndCreateToken(credentials.username, credentials.password)) {
        case Success(Some(token)) => complete(token)
        case _ => complete(HttpResponse(status = StatusCodes.Forbidden))
      }
    }

  def validateCredentialsFormat: Directive[Tuple1[UserCredentialsDTO]] = withDTOValidation[UserCredentialsDTO]
}
