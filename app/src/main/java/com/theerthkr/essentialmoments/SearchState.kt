package com.theerthkr.essentialmoments

data class SearchResult(val uri: String, val score: Float)

/**
 * Outcome of the most recently submitted search. Independent of the draft text in the
 * search box: typing never changes this, only submitting (or clearing) does.
 */
sealed interface SearchState {
    data object Idle : SearchState
    data class Searching(val query: String) : SearchState
    data class Results(val query: String, val results: List<SearchResult>) : SearchState
    data class NoResults(val query: String) : SearchState
    data class Error(val query: String) : SearchState
}

/** What the search screen's content area should show. */
sealed interface SearchContent {
    data object EmptyQuery : SearchContent
    data object Searching : SearchContent

    /** Text has been entered but not searched yet. */
    data class Pending(val query: String) : SearchContent
    data class Results(val query: String, val results: List<SearchResult>) : SearchContent
    data class NoResults(val query: String) : SearchContent
    data class Error(val query: String) : SearchContent
}

/**
 * Decides what the content area shows from the draft text and the last search outcome.
 *
 * "No results" and errors are only shown while the draft still matches the query that
 * produced them; editing the text switches to [SearchContent.Pending]. A previous result
 * grid stays visible while editing so it doesn't flash away on every keystroke.
 */
fun resolveSearchContent(draft: String, state: SearchState): SearchContent {
    val query = draft.trim()
    return when {
        state is SearchState.Searching -> SearchContent.Searching
        query.isEmpty() -> SearchContent.EmptyQuery
        state is SearchState.Results -> SearchContent.Results(state.query, state.results)
        state is SearchState.NoResults && state.query == query -> SearchContent.NoResults(query)
        state is SearchState.Error && state.query == query -> SearchContent.Error(query)
        else -> SearchContent.Pending(query)
    }
}
