package org.briarproject.briar.headless.forums

import io.javalin.http.Context

interface ForumController {

    fun list(ctx: Context): Context
    fun create(ctx: Context): Context

    fun get(ctx: Context): Context
    fun update(ctx: Context): Context
    fun delete(ctx: Context): Context

    fun getLink(ctx: Context): Context
    fun listPendingForums(ctx: Context): Context
    fun addPendingForum(ctx: Context): Context
    fun acceptPendingForum(ctx: Context): Context
    fun removePendingForum(ctx: Context): Context

    fun getPostCount(ctx: Context): Context

}
