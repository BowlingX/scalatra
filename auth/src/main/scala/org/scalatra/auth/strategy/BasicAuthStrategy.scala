package org.scalatra
package auth
package strategy

import javax.servlet.http.HttpServletRequest
import org.scalatra.auth.{ScentrySupport, ScentryStrategy}
import net.iharder.Base64
import java.nio.charset.Charset
import java.util.Locale

trait RemoteAddress { self: ScentryStrategy[_]  =>

  protected def remoteAddress ={
    val proxied = app.request.getHeader("X-FORWARDED-FOR")
    val res = if (proxied.isNonBlank) proxied else app.request.getRemoteAddr
    res
  }
}

/**
 * Provides a hook for the basic auth strategy
 *
 * for more details on usage check:
 * https://gist.github.com/732347
 */
trait BasicAuthSupport[UserType <: AnyRef] { self: ScentrySupport[UserType]  =>

  val realm: String

  protected def basicAuth() = scentry.authenticate('Basic)

}

object BasicAuthStrategy {

  private val AUTHORIZATION_KEYS = List("Authorization", "HTTP_AUTHORIZATION", "X-HTTP_AUTHORIZATION", "X_HTTP_AUTHORIZATION")
  private class BasicAuthRequest(r: HttpServletRequest) {

    def parts = authorizationKey map { r.getHeader(_).split(" ", 2).toList } getOrElse Nil
    def scheme: Option[Symbol] = parts.headOption.map(sch => Symbol(sch.toLowerCase(Locale.ENGLISH)))
    def params = parts.tail.headOption

    private def authorizationKey = AUTHORIZATION_KEYS.find(r.getHeader(_) != null)

    def isBasicAuth = (false /: scheme) { (_, sch) => sch == 'basic }
    def providesAuth = authorizationKey.isDefined

    private var _credentials: Option[(String, String)] = None
    def credentials = {
      if (_credentials.isEmpty )
        _credentials = params map { p =>
          (null.asInstanceOf[(String, String)] /: new String(Base64.decode(p), Charset.forName("UTF-8")).split(":", 2)) { (t, l) =>
            if(t == null) (l, null) else (t._1, l)
          }
        }
      _credentials
    }
    def username = credentials map { _._1 } getOrElse null
    def password = credentials map { _._2 } getOrElse null
  }
}
abstract class BasicAuthStrategy[UserType <: AnyRef](protected val app: ScalatraKernel, realm: String)
  extends ScentryStrategy[UserType]
  with RemoteAddress {

  import BasicAuthStrategy._

  implicit def request2BasicAuthRequest(r: HttpServletRequest) = new BasicAuthRequest(r)

  protected def challenge = "Basic realm=\"%s\"" format realm

  def authenticate() = {
    val req = app.request
    val res = app.response

    if(!req.providesAuth) {
      unauthorized()
    }
    else if (!req.isBasicAuth) {
      badRequest
    }
    else {
      val u = validate(req.username, req.password)
      if (u.isDefined) {
        res.setHeader("REMOTE_USER", getUserId(u.get))
        u
      } else {
        unauthorized()
      }
    }
  }

  protected def getUserId(user: UserType): String
  protected def validate(userName: String, password: String): Option[UserType]

  def unauthorized(value: String = challenge): Option[UserType] = {
    app.response.setHeader("WWW-Authenticate", value)
    app.halt(401, "Unauthenticated")
    None
  }
  def badRequest: Option[UserType] = {
    app.halt(400, " Bad request")
    None
  }
}