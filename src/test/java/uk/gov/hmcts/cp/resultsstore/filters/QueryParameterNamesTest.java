package uk.gov.hmcts.cp.resultsstore.filters;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** Parameter names read from the raw query string alone, decoded as the container decodes them. */
@DisplayName("query parameter names")
class QueryParameterNamesTest {

    private static final String NAME = "storedAfterSeq";

    @ParameterizedTest
    @ValueSource(strings = {"storedAfterSeq=0", "storedAfterSeq", "storedAfterSeq=", "limit=5&storedAfterSeq=0",
        "&&storedAfterSeq=1&", "stored%41fterSeq=1", "stored%41fter%53eq", "storedAfterSeq=a=b",
        "courtCentreId=x&storedAfterSeq=%zz"})
    void a_pair_whose_name_decodes_to_the_name_should_be_found(final String rawQuery) {
        assertThat(QueryParameterNames.contains(rawQuery, NAME)).isTrue();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"storedafterseq=0", "StoredAfterSeq=0", "xstoredAfterSeq=1", "storedAfterSeqx=1",
        "courtCentreId=storedAfterSeq", "storedAfterSeq%3D=1", "stored+AfterSeq=1", "storedAfterSeq%zz=1",
        "storedAfterSeq%=1", "storedAfterSeq%4=1", "%=1", "=storedAfterSeq", "storedAfterSeq%00=1",
        "stored%\uFF14\uFF11fterSeq=1", "storedAfterSeq%", "storedAfterSeq%4"})
    void any_other_query_should_not_find_the_name(final String rawQuery) {
        assertThat(QueryParameterNames.contains(rawQuery, NAME)).isFalse();
    }
}
