package com.example.weather.api.v1;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Unit test: parse strictness of the D-1 fix ({@link DaysParamValidator}), plain Hibernate Validator, no Spring. */
class DaysParamValidatorTest {

    private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();
    private static final Validator VALIDATOR = FACTORY.getValidator();

    @AfterAll
    static void close() {
        FACTORY.close();
    }

    private static Set<String> messages(String days) {
        return VALIDATOR.validate(new ForecastQuery("London", null, days)).stream()
                .map(ConstraintViolation::getMessage)
                .collect(Collectors.toSet());
    }

    @ParameterizedTest
    @ValueSource(strings = {"#3", "0X3", "3L", "3.0", "1e0", "3,4", "--3", "+-3", " 3", "2147483648", "-2147483649",
            "٣", "３", "१"})
    @DisplayName("AC-7 (unit, D-1 edge): other non-decimal forms (incl. non-ASCII digits) are rejected with the non-integer message")
    void ac7_nonDecimalFormsRejected(String days) {
        assertThat(messages(days)).containsExactly(DaysParam.NOT_AN_INTEGER);
    }

    @ParameterizedTest
    @ValueSource(strings = {"-0", "00", "-1", "+8", "2147483647", "-2147483648"})
    @DisplayName("AC-7 (unit, D-1 edge): well-formed integers outside 1..7 get the range message, not the non-integer one")
    void ac7_outOfRangeIntegersGetRangeMessage(String days) {
        assertThat(messages(days)).containsExactly(DaysParam.OUT_OF_RANGE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"03", "+3", "\t3\n", " 3 "})
    @DisplayName("AC-5 (unit, D-1 edge): leading zeros, a plus sign and surrounding whitespace are accepted")
    void ac5_lenientButDecimalFormsAccepted(String days) {
        assertThat(messages(days)).isEmpty();
        assertThat(new ForecastQuery("London", null, days).resolvedDays()).isEqualTo(3);
    }

    @Test
    @DisplayName("AC-7 (unit): both days messages state the contract range 1..7")
    void ac7_messagesMatchContractRange() {
        assertThat(DaysParam.MIN).isEqualTo(1);
        assertThat(DaysParam.MAX).isEqualTo(7);
        assertThat(DaysParam.NOT_AN_INTEGER).isEqualTo("must be an integer between 1 and 7");
        assertThat(DaysParam.OUT_OF_RANGE).isEqualTo("must be between 1 and 7");
    }

    @Test
    @DisplayName("AC-6 (unit): absent or empty days is valid and resolves to the default 5")
    void ac6_absentOrEmptyIsDefault() {
        assertThat(messages(null)).isEmpty();
        assertThat(messages("")).isEmpty();
        assertThat(new ForecastQuery("London", null, "").days()).isNull();
    }

    @Test
    @DisplayName("Edge: the raw validator rejects an empty string (only the record normalises it to absent)")
    void edge_rawValidatorRejectsEmpty() {
        assertThat(DaysParamValidator.parse("")).isEmpty();
        assertThat(DaysParamValidator.parse("   ")).isEmpty();
    }

    @Test
    @DisplayName("Edge: resolvedDays on an unvalidated non-integer fails fast instead of defaulting")
    void edge_resolvedDaysOnInvalidThrows() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new ForecastQuery("London", null, "abc").resolvedDays())
                .isInstanceOf(IllegalStateException.class);
    }
}
