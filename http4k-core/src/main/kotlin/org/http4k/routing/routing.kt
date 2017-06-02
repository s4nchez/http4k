package org.http4k.routing

import org.http4k.core.Filter
import org.http4k.core.HttpHandler
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status.Companion.NOT_FOUND
import org.http4k.core.UriTemplate
import org.http4k.core.UriTemplate.Companion.from
import org.http4k.core.findSingle
import org.http4k.core.then

data class Route(val method: Method, val template: UriTemplate, val handler: HttpHandler)

fun routes(vararg routes: Route): GroupRoutingHttpHandler = GroupRoutingHttpHandler(null, routes.asList())

fun routes(first: Router, vararg then: Router): HttpHandler = then.fold(first) { memo, next -> memo.then(next) }.toHttpHandler()

fun Request.path(name: String): String? = uriTemplate().extract(uri.toString())[name]

infix fun Pair<Method, String>.by(action: HttpHandler): Route = Route(first, from(second), action)

infix fun String.by(router: RoutingHttpHandler): RoutingHttpHandler = router.withBasePath(this)

interface RoutingHttpHandler : Router, HttpHandler {
    fun withBasePath(basePath: String): RoutingHttpHandler
}

data class GroupRoutingHttpHandler(private val basePath: UriTemplate? = null, private val routes: List<Route>, private val filter: Filter? = null) : RoutingHttpHandler {
    private val routers = routes.map(Route::asRouter)
    private val noMatch: HttpHandler? = null

    override fun withBasePath(basePath: String) = GroupRoutingHttpHandler(from(basePath),
        routes.map { it.copy(template = it.template.prefixedWith(basePath)) })

    override fun invoke(request: Request): Response = match(request)
        ?.let { handler -> (filter?.then(handler) ?: handler).invoke(request) }
        ?: Response(NOT_FOUND.description("Route not found"))

    override fun match(request: Request): HttpHandler? =
        if (basePath?.matches(request.uri.path) ?: true) routers.fold(noMatch, { memo, router -> memo ?: router.match(request) })
        else null
}

private fun Route.asRouter(): Router = object : Router {
    override fun match(request: Request): HttpHandler? =
        if (template.matches(request.uri.path) && method == request.method) {
            { req: Request -> handler(req.withUriTemplate(template)) }
        } else null
}

private fun Request.withUriTemplate(uriTemplate: UriTemplate): Request = header("x-uri-template", uriTemplate.toString())

private fun Request.uriTemplate(): UriTemplate = headers.findSingle("x-uri-template")?.let { UriTemplate.from(it) } ?: throw IllegalStateException("x-uri-template header not present in the request")