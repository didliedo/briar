package org.briarproject.briar.headless.forums

import com.fasterxml.jackson.databind.ObjectMapper
import io.javalin.http.BadRequestResponse
import io.javalin.http.Context
import org.briarproject.bramble.api.contact.ContactId
import org.briarproject.bramble.api.db.DbException
import org.briarproject.bramble.util.StringUtils.utf8IsTooLong
import org.briarproject.briar.api.forum.ForumConstants.MAX_FORUM_NAME_LENGTH
import org.briarproject.briar.api.forum.Forum
import org.briarproject.briar.api.forum.ForumManager
import org.briarproject.briar.api.forum.ForumSharingManager
import org.briarproject.briar.api.sharing.SharingInvitationItem
import org.briarproject.briar.headless.getFromJson
import org.briarproject.bramble.api.sync.GroupId;
import javax.annotation.concurrent.Immutable
import javax.inject.Inject
import javax.inject.Singleton
import java.util.Base64
import java.util.logging.Level.INFO
import java.util.logging.Logger.getLogger

@Immutable
@Singleton
internal class ForumControllerImpl
@Inject
constructor(
    private val forumManager: ForumManager,
    private val forumSharingManager: ForumSharingManager,
    private val objectMapper: ObjectMapper

) : ForumController {

    private val logger = getLogger(ForumControllerImpl::javaClass.name)

    override fun list(ctx: Context): Context {
        return ctx.json(forumManager.forums.output())
    }

    override fun create(ctx: Context): Context {
        val name = ctx.getFromJson(objectMapper, "name")
        if (utf8IsTooLong(name, MAX_FORUM_NAME_LENGTH))
            throw BadRequestResponse("Forum name is too long")
        return ctx.json(forumManager.addForum(name).output())
    }

    override fun addPendingForum(ctx: Context): Context {
        val forumIdStr = ctx.getFromJson(objectMapper, "forumId")
        val contactIdStr = ctx.getFromJson(objectMapper, "contactId")
        val text = try {
            val jsonNode = objectMapper.readTree(ctx.body())
            jsonNode.get("text")?.asText() ?: ""
        } catch (e: Exception) {
            ""
        }

        try {
            val forumId = GroupId(Base64.getDecoder().decode(forumIdStr))
            val contactId = ContactId(contactIdStr.toInt())
            forumSharingManager.sendInvitation(forumId, contactId, text)
            return ctx.status(204)
        } catch (e: DbException) {
            throw BadRequestResponse("Failed to send invitation: ${e.message}")
        } catch (e: IllegalArgumentException) {
            throw BadRequestResponse("Invalid forumId or contactId")
        }
    }

    override fun listPendingForums(ctx: Context): Context {
        return try {
            val invitations = forumSharingManager.invitations.map { it.toMap() }
            ctx.json(invitations)
        } catch (e: DbException) {
            throw BadRequestResponse("Failed to list invitations: ${e.message}")
        }
    }

    override fun removePendingForum(ctx: Context): Context {
        // Not Implemented
        return ctx.status(501) // Not Implemented
    }

    override fun get(ctx: Context): Context {
        val forumIdStr = ctx.pathParam("forumId")
        try {
            println("Raw forumId: $forumIdStr") // Debugging output

            val forumIdBytes = Base64.getDecoder().decode(forumIdStr)
            println("Decoded forumId bytes: ${forumIdBytes.joinToString { "%02x".format(it) }}") // Debugging output

            val forumId = GroupId(forumIdBytes)
            println("Decoded forumId: $forumId") // Debugging output

            val forum = forumManager.getForum(forumId)
            println("Fetched forum: $forum") // Debugging output

            return ctx.json(forum)
        } catch (e: DbException) {
            println("DbException occurred: ${e.message}") // Debugging output
            throw BadRequestResponse("Failed to fetch forum: ${e.message}")
        } catch (e: IllegalArgumentException) {
            println("IllegalArgumentException occurred: ${e.message}") // Debugging output
            throw BadRequestResponse("Invalid forumId")
        }
    }

    override fun update(ctx: Context): Context {
        // Not Implemented
        return ctx.status(501) // Not Implemented
    }

    override fun delete(ctx: Context): Context {
        // Not Implemented
        return ctx.status(501) // Not Implemented
    }

    override fun getLink(ctx: Context): Context {
        // Not Implemented
        return ctx.status(501) // Not Implemented
    }

    override fun acceptPendingForum(ctx: Context): Context {
        val forumIdStr = ctx.getFromJson(objectMapper, "forumId")
        val contactIdStr = ctx.getFromJson(objectMapper, "contactId")

        try {
            val forumIdBytes = forumIdStr.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val forumIdBase64 = Base64.getEncoder().encodeToString(forumIdBytes)

            val forumId = GroupId(Base64.getDecoder().decode(forumIdBase64))
            val contactId = ContactId(contactIdStr.toInt())
            val invitations = forumSharingManager.invitations

            val invitation = invitations.find {
                it.shareable.id == forumId && it.newSharers.any { sharer -> sharer.id == contactId }
            }
            if (invitation != null) {
                val forum = invitation.shareable as Forum
                val contact = invitation.newSharers.first { it.id == contactId }
                forumSharingManager.respondToInvitation(forum, contact, true)
                return ctx.status(204)
            } else {
                throw BadRequestResponse("Invitation not found")
            }
        } catch (e: DbException) {
            throw BadRequestResponse("Failed to accept invitation: ${e.message}")
        } catch (e: IllegalArgumentException) {
            throw BadRequestResponse("Invalid forumId or contactId")
        }
    }

    override fun getPostCount(ctx: Context): Context {
        val forumIdStr = ctx.pathParam("forumId")
        try {
            println("Raw forumId: $forumIdStr") 

            val forumIdBytes = Base64.getDecoder().decode(forumIdStr)

            val forumId = GroupId(forumIdBytes)

            val postHeaders = forumManager.getPostHeaders(forumId)
            val postCount = postHeaders.size

            val lastPostDate = postHeaders.maxOfOrNull { it.timestamp } ?: "No posts yet"
            val firstPostDate = postHeaders.minOfOrNull { it.timestamp } ?: "No posts yet"

            val postCountDetails = mapOf(
                "forumId" to forumId.toString(),
                "postCount" to postCount,
                "lastPostDate" to lastPostDate,
                "firstPostDate" to firstPostDate
            )

            return ctx.json(postCountDetails)
        } catch (e: DbException) {
            throw BadRequestResponse("Failed to fetch post count: ${e.message}")
        } catch (e: IllegalArgumentException) {
            throw BadRequestResponse("Invalid forumId")
        }
    }
}

private fun SharingInvitationItem.toMap(): Map<String, Any?> {
    return mapOf(
        "forumId" to shareable.id.toString(),
        "forumName" to shareable.name,
        "newSharers" to newSharers.map { it.id.toString() },
        "subscribed" to isSubscribed()
    )
}