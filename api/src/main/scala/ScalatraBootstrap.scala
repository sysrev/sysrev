import javax.servlet.ServletContext

import co.insilica.auth.AuthServlet
import co.insilica.sysrev.api.SysrevServlet
import org.scalatra._
import javax.servlet.ServletContext

class ScalatraBootstrap extends LifeCycle {
  override def init(context: ServletContext): Unit = {
    context.mount(new SysrevServlet, "/*")
    context.mount(new AuthServlet, "/auth/*")
  }
}
