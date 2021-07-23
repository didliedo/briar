package org.briarproject.briar.headless.contact

import org.briarproject.bramble.api.contact.HandshakeLinkConstants.BASE32_LINK_BYTES
import org.briarproject.briar.headless.IntegrationTest
import org.briarproject.briar.headless.url
import org.briarproject.briar.test.BriarTestUtils.getRealHandshakeLink
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BlogControllerIntegrationTest: IntegrationTest() {

    @Test
    fun `get blog posts`() {
        val response = get("$url/blogs/posts")
        assertEquals(200, response.statusCode)
    }

    @Test
    fun `posting a blog post`() {
        val response = post("$url/blogs/posts","{\"text\":\"Hello!\"}")
        assertEquals(200, response.statusCode)
    }

    @Test
    fun `posting a blog needs authentication token`() {
        val response = postWithWrongToken("$url/blogs/posts")
        assertEquals(401, response.statusCode)
    }

    @Test
    fun `get blog posts needs authentication token`() {
        val response = getWithWrongToken("$url/blogs/posts")
        assertEquals(401, response.statusCode)
    }

}
