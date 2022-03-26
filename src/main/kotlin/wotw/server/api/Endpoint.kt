package wotw.server.api

import io.ktor.application.*
import io.ktor.auth.*
import io.ktor.routing.*
import io.ktor.util.pipeline.*
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import wotw.server.database.model.User
import wotw.server.exception.ForbiddenException
import wotw.server.exception.UnauthorizedException
import wotw.server.main.WotwBackendServer
import java.nio.channels.Pipe

abstract class Endpoint(val server: WotwBackendServer) {
    fun init(routing: Route) = routing.initRouting()
    protected abstract fun Route.initRouting()

    fun ApplicationCall.wotwPrincipalOrNull(): WotwUserPrincipal? {
        return principal()
    }

    fun ApplicationCall.wotwPrincipal(): WotwUserPrincipal {
        return wotwPrincipalOrNull() ?: throw UnauthorizedException()
    }

    fun PipelineContext<Unit, ApplicationCall>.wotwPrincipalOrNull(): WotwUserPrincipal? {
        return call.principal()
    }

    fun PipelineContext<Unit, ApplicationCall>.wotwPrincipal(): WotwUserPrincipal {
        return wotwPrincipalOrNull() ?: throw UnauthorizedException()
    }

    fun PipelineContext<Unit, ApplicationCall>.authenticatedUserOrNull(): User? {
        val id = wotwPrincipalOrNull()?.userId ?: return null
        return User.findById(id)
    }

    fun PipelineContext<Unit, ApplicationCall>.authenticatedUser(): User {
        return authenticatedUserOrNull() ?: throw UnauthorizedException()
    }

    suspend fun PipelineContext<Unit, ApplicationCall>.requireAdmin() {
        val isAdmin = newSuspendedTransaction {
            authenticatedUser().isAdmin
        }

        if (!isAdmin) {
            throw ForbiddenException()
        }
    }
}