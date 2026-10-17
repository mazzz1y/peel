package wtf.mazy.peel.ui.proxy

import android.content.Context
import wtf.mazy.peel.R
import wtf.mazy.peel.model.Proxy
import wtf.mazy.peel.model.ProxyChoice
import wtf.mazy.peel.model.ProxyRoute
import wtf.mazy.peel.model.WebAppGroup
import wtf.mazy.peel.model.effectiveSandbox

data class ProxyOption<out P : ProxyChoice>(
    val choice: P,
    val label: String,
    val routeLabel: String,
)

object ProxyOptions {

    fun forWebApp(
        context: Context,
        group: WebAppGroup?,
        proxies: List<Proxy>,
    ): List<ProxyOption<ProxyChoice>> {
        val routes = routes(context, proxies)
        val inherit = group?.let {
            val groupRoute = routeLabel(context, effectiveSandbox(it).route, proxies)
            ProxyOption(
                choice = ProxyChoice.Inherit,
                label = context.getString(R.string.proxy_inherit_group, groupRoute),
                routeLabel = groupRoute,
            )
        }
        return listOfNotNull(inherit) + routes
    }

    fun forGroup(context: Context, proxies: List<Proxy>): List<ProxyOption<ProxyRoute>> =
        routes(context, proxies)

    fun <P : ProxyChoice> current(options: List<ProxyOption<P>>, choice: P): ProxyOption<P> =
        options.firstOrNull { it.choice == choice }
            ?: options.first { it.choice == ProxyRoute.Direct }

    private fun routes(context: Context, proxies: List<Proxy>): List<ProxyOption<ProxyRoute>> {
        val direct = context.getString(R.string.proxy_direct)
        return listOf(ProxyOption(ProxyRoute.Direct, direct, direct)) +
                proxies.sortedBy { it.displayName().lowercase() }.map { proxy ->
                    ProxyOption(ProxyRoute.Via(proxy.uuid), proxy.displayName(), proxy.displayName())
                }
    }

    private fun routeLabel(context: Context, route: ProxyRoute, proxies: List<Proxy>): String =
        when (route) {
            ProxyRoute.Direct -> null
            is ProxyRoute.Via -> proxies.find { it.uuid == route.proxyUuid }?.displayName()
        } ?: context.getString(R.string.proxy_direct)
}
