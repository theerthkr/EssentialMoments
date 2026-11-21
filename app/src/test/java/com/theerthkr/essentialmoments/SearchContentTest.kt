package com.theerthkr.essentialmoments

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchContentTest {

    private val results = listOf(SearchResult(uri = "content://media/1", score = 0.9f))

    @Test
    fun blankDraftShowsEmptyQueryHint() {
        assertEquals(SearchContent.EmptyQuery, resolveSearchContent("", SearchState.Idle))
        assertEquals(SearchContent.EmptyQuery, resolveSearchContent("   ", SearchState.Idle))
    }

    @Test
    fun typingWithoutSubmittingShowsPendingNotNoResults() {
        assertEquals(SearchContent.Pending("dog"), resolveSearchContent("dog", SearchState.Idle))
    }

    @Test
    fun pendingQueryIsTrimmed() {
        assertEquals(SearchContent.Pending("dog"), resolveSearchContent("  dog ", SearchState.Idle))
    }

    @Test
    fun searchingShowsSearchingEvenWhileEditing() {
        assertEquals(
            SearchContent.Searching,
            resolveSearchContent("dogs", SearchState.Searching("dog"))
        )
    }

    @Test
    fun noResultsShownOnlyForTheSubmittedQuery() {
        val state = SearchState.NoResults("dog")
        assertEquals(SearchContent.NoResults("dog"), resolveSearchContent("dog", state))
        assertEquals(SearchContent.NoResults("dog"), resolveSearchContent("dog ", state))
        assertEquals(SearchContent.Pending("dogs"), resolveSearchContent("dogs", state))
    }

    @Test
    fun errorShownOnlyForTheSubmittedQuery() {
        val state = SearchState.Error("dog")
        assertEquals(SearchContent.Error("dog"), resolveSearchContent("dog", state))
        assertEquals(SearchContent.Pending("cat"), resolveSearchContent("cat", state))
    }

    @Test
    fun resultsStayVisibleWhileEditing() {
        val state = SearchState.Results("dog", results)
        assertEquals(SearchContent.Results("dog", results), resolveSearchContent("dog", state))
        assertEquals(SearchContent.Results("dog", results), resolveSearchContent("dogs", state))
    }
}
