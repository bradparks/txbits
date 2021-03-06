/**
 * Copyright 2012-2014 Jorge Aliss (jaliss at gmail dot com) - twitter: @jaliss
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package controllers

import _root_.java.util.UUID
import play.api.mvc.{ Result, Action, Controller }
import play.api.data._
import play.api.data.Forms._
import play.api.data.validation.Constraints._
import play.api.{ Play, Logger }
import securesocial.core._
import com.typesafe.plugin._
import Play.current
import securesocial.core.providers.utils._
import org.joda.time.DateTime
import play.api.i18n.Messages
import scala.language.reflectiveCalls
import securesocial.core.Token
import scala.Some
import securesocial.core.SocialUser
import service.{ PGP, txbitsUserService }
import models.{ LogType, LogEvent }
import java.security.SecureRandom

/**
 * A controller to handle user registration.
 *
 */
object Registration extends Controller {

  val PasswordsDoNotMatch = "securesocial.signup.passwordsDoNotMatch"
  val PgpKeyInvalid = "securesocial.signup.pgpKeyInvalid"
  val ThankYouCheckEmail = "securesocial.signup.thankYouCheckEmail"
  val InvalidLink = "securesocial.signup.invalidLink"
  val SignUpDone = "securesocial.signup.signUpDone"
  val PasswordUpdated = "securesocial.password.passwordUpdated"
  val ErrorUpdatingPassword = "securesocial.password.error"

  val MailingList = "mailinglist"
  val Password = "password"
  val Password1 = "password1"
  val Password2 = "password2"
  val Email = "email"
  val Success = "success"
  val Error = "error"
  val Pgp = "pgp"

  val RegistrationEnabled = "securesocial.registrationEnabled"

  /** The redirect target of the handleStartSignUp action. */
  val onHandleStartSignUpGoTo = stringConfig("securesocial.onStartSignUpGoTo", controllers.routes.LoginPage.login().url)
  /** The redirect target of the handleSignUp action. */
  val onHandleSignUpGoTo = stringConfig("securesocial.onSignUpGoTo", controllers.routes.LoginPage.login().url)
  /** The redirect target of the handleStartResetPassword action. */
  val onHandleStartResetPasswordGoTo = stringConfig("securesocial.onStartResetPasswordGoTo", controllers.routes.LoginPage.login().url)
  /** The redirect target of the handleResetPassword action. */
  val onHandleResetPasswordGoTo = stringConfig("securesocial.onResetPasswordGoTo", controllers.routes.LoginPage.login().url)

  lazy val registrationEnabled = current.configuration.getBoolean(RegistrationEnabled).getOrElse(true)

  private def stringConfig(key: String, default: => String) = {
    Play.current.configuration.getString(key).getOrElse(default)
  }

  case class RegistrationInfo(mailingList: Boolean, password: String, pgp: String)

  val form = Form[RegistrationInfo](
    mapping(
      MailingList -> boolean,
      Password ->
        tuple(
          Password1 -> nonEmptyText.verifying(PasswordValidator.validator),
          Password2 -> nonEmptyText
        ).verifying(Messages(PasswordsDoNotMatch), passwords => passwords._1 == passwords._2),
      Pgp -> text.verifying(Messages(PgpKeyInvalid), pgp => pgp == "" || PGP.parsePublicKey(pgp).isDefined)
    ) // binding
    ((list, password, pgp) => RegistrationInfo(list, password._1, pgp)) // unbinding
    (info => Some(info.mailingList, ("", ""), info.pgp))
  )

  val startForm = Form(
    Email -> email.verifying(nonEmpty)
  )

  val changePasswordForm = Form(
    Password ->
      tuple(
        Password1 -> nonEmptyText.verifying(PasswordValidator.validator),
        Password2 -> nonEmptyText
      ).verifying(Messages(PasswordsDoNotMatch), passwords => passwords._1 == passwords._2)
  )

  /**
   * Starts the sign up process
   */
  def startSignUp = Action { implicit request =>
    if (registrationEnabled) {
      if (SecureSocial.enableRefererAsOriginalUrl) {
        SecureSocial.withRefererAsOriginalUrl(Ok(SecureSocialTemplates.getStartSignUpPage(request, startForm)))
      } else {
        Ok(SecureSocialTemplates.getStartSignUpPage(request, startForm))
      }
    } else NotFound(views.html.defaultpages.notFound.render(request, None))
  }

  val random = new SecureRandom()

  def handleStartSignUp = Action { implicit request =>
    if (registrationEnabled) {
      startForm.bindFromRequest.fold(
        errors => {
          BadRequest(SecureSocialTemplates.getStartSignUpPage(request, errors))
        },
        email => {
          txbitsUserService.signupStart(email)
          Redirect(onHandleStartSignUpGoTo).flashing(Success -> Messages(ThankYouCheckEmail), Email -> email)
        }
      )
    } else NotFound(views.html.defaultpages.notFound.render(request, None))
  }

  /**
   * Renders the sign up page
   * @return
   */
  def signUp(token: String) = Action { implicit request =>
    if (registrationEnabled) {
      if (Logger.isDebugEnabled) {
        Logger.debug("[securesocial] trying sign up with token %s".format(token))
      }
      executeForToken(token, true, { _ =>
        Ok(SecureSocialTemplates.getSignUpPage(request, form, token))
      })
    } else NotFound(views.html.defaultpages.notFound.render(request, None))
  }

  private def executeForToken(token: String, isSignUp: Boolean, f: Token => Result): Result = {
    txbitsUserService.findToken(token) match {
      case Some(t) if !t.isExpired && t.isSignUp == isSignUp => {
        f(t)
      }
      case _ => {
        val to = if (isSignUp) controllers.routes.Registration.startSignUp() else controllers.routes.Registration.startResetPassword()
        Redirect(to).flashing(Error -> Messages(InvalidLink))
      }
    }
  }

  /**
   * Handles posts from the sign up page
   */
  def handleSignUp(token: String) = Action { implicit request =>
    if (registrationEnabled) {
      executeForToken(token, true, { t =>
        form.bindFromRequest.fold(
          errors => {
            if (Logger.isDebugEnabled) {
              Logger.debug("[securesocial] errors " + errors)
            }
            BadRequest(SecureSocialTemplates.getSignUpPage(request, errors, t.uuid))
          },
          info => {
            val user = txbitsUserService.create(SocialUser(
              -1, // this is a placeholder
              t.email,
              0, //not verified
              info.mailingList
            ), info.password, token, info.pgp)
            txbitsUserService.deleteToken(t.uuid)
            if (UsernamePasswordProvider.sendWelcomeEmail) {
              Mailer.sendWelcomeEmail(user)
            }
            globals.logModel.logEvent(LogEvent.fromRequest(Some(user.id), Some(user.email), request, LogType.SignupSuccess))
            if (UsernamePasswordProvider.signupSkipLogin) {
              ProviderController.completePasswordAuth(user.id, user.email)
            } else {
              Redirect(onHandleSignUpGoTo).flashing(Success -> Messages(SignUpDone)).withSession(request2session)
            }
          }
        )
      })
    } else NotFound(views.html.defaultpages.notFound.render(request, None))
  }

  def startResetPassword = Action { implicit request =>
    Ok(SecureSocialTemplates.getStartResetPasswordPage(request, startForm))
  }

  def handleStartResetPassword = Action { implicit request =>
    startForm.bindFromRequest.fold(
      errors => {
        BadRequest(SecureSocialTemplates.getStartResetPasswordPage(request, errors))
      },
      email => {
        txbitsUserService.userExists(email) match {
          case true => {
            txbitsUserService.resetPassStart(email)
          }
          case false => {
            // The user wasn't registered. Oh, well.
          }
        }
        Redirect(onHandleStartResetPasswordGoTo).flashing(Success -> Messages(ThankYouCheckEmail))
      }
    )
  }

  def resetPassword(token: String) = Action { implicit request =>
    executeForToken(token, false, { t =>
      Ok(SecureSocialTemplates.getResetPasswordPage(request, changePasswordForm, token))
    })
  }

  def handleResetPassword(token: String) = Action { implicit request =>
    executeForToken(token, false, { t =>
      changePasswordForm.bindFromRequest.fold(errors => {
        BadRequest(SecureSocialTemplates.getResetPasswordPage(request, errors, token))
      },
        p => {
          val toFlash = txbitsUserService.userExists(t.email) match {
            case true => {
              // this should never actually fail because we checked the token already
              txbitsUserService.resetPass(t.email, token, p._1)
              txbitsUserService.deleteToken(token)
              Mailer.sendPasswordChangedNotice(t.email, globals.userModel.userPgpByEmail(t.email))
              Success -> Messages(PasswordUpdated)
            }
            case false => {
              Logger.error("[securesocial] could not find user with email %s during password reset".format(t.email))
              Error -> Messages(ErrorUpdatingPassword)
            }
          }
          Redirect(onHandleResetPasswordGoTo).flashing(toFlash)
        })
    })
  }
}
