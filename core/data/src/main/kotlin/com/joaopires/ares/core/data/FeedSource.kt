// SPDX-License-Identifier: GPL-3.0-or-later

package com.joaopires.ares.core.data

import com.joaopires.ares.core.feed.DiscoveryOutcome
import com.joaopires.ares.core.feed.FeedClient
import com.joaopires.ares.core.feed.FetchOutcome
import com.joaopires.ares.core.feed.Validators

public interface FeedSource {
    public suspend fun fetch(url: String, validators: Validators?, feedKey: String): FetchOutcome
    public suspend fun discover(url: String): DiscoveryOutcome
}

public class FeedClientSource(private val client: FeedClient) : FeedSource {
    override suspend fun fetch(url: String, validators: Validators?, feedKey: String): FetchOutcome =
        client.fetch(url, validators, feedKey)

    override suspend fun discover(url: String): DiscoveryOutcome = client.discover(url)
}
