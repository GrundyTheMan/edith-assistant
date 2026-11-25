package com.edith.app.microsoft

import com.edith.app.testing.assertEquals
import io.ktor.http.Url
import kotlin.test.Test

/** K2.6c: what the Graph client writes to the log, run on the desktop and the iPhone simulator too. The request tests are `OutlookSourcesTest` in `app/`. */
class GraphClientTest {
    @Test fun aRequestIsLoggedByKindWithEveryIdLeftOut() {
        val url = Url("https://graph.microsoft.com/v1.0/me/calendars/AAMk123/events/AAMk456")
        assertEquals("GET me/calendars/…/events/…", GraphClient.requestKind("GET", url))
    }

    @Test fun knownSegmentsStayAndOthersBecomeDots() {
        val url = Url("https://graph.microsoft.com/v1.0/me/messages/AAMk789/createReply")
        assertEquals("POST me/messages/…/createReply", GraphClient.requestKind("POST", url))
    }

    @Test fun graphsErrorCodeIsReadButNeverItsMessage() {
        assertEquals("ErrorItemNotFound", GraphClient.graphCode("""{"error":{"code":"ErrorItemNotFound","message":"secret words"}}"""))
        assertEquals("no code", GraphClient.graphCode("not json"))
    }

    @Test fun aSlashInAnIdIsEncodedInTheUrl() {
        val client = GraphClient(
            http = lazy { error("no request is made") },
            auth = object : GraphAuth {
                override suspend fun <T> authorized(call: suspend (token: String) -> GraphCall<T>) = error("no request is made")
            },
            dispatchers = com.edith.app.core.dispatchers.AppDispatchers.Default,
            tool = "email",
            service = "Outlook",
            notFound = "gone",
        )
        // The slash must not end the segment; ktor leaves a plus sign and an equals sign as they are, which a path allows.
        assertEquals("https://graph.microsoft.com/v1.0/me/messages/a%2Fb+c=", client.url("me", "messages", "a/b+c=").toString())
    }
}
