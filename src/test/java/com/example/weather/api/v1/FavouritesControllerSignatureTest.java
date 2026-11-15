package com.example.weather.api.v1;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.weather.api.ClientId;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.RequestMapping;

/** Unit test (reflection): the ADR 0009/0010 handler-signature rules. No Spring context, no Docker. */
class FavouritesControllerSignatureTest {

    private static List<Method> handlerMethods() {
        return Arrays.stream(FavouritesController.class.getDeclaredMethods())
                .filter(m -> AnnotatedElementUtils.hasAnnotation(m, RequestMapping.class))
                .toList();
    }

    @Test
    @DisplayName("AC-15: every handler method takes ClientId as its first parameter")
    void clientIdFirst() {
        assertThat(handlerMethods()).hasSize(6);
        for (Method m : handlerMethods()) {
            assertThat(m.getParameterTypes()).as(m.getName()).isNotEmpty();
            assertThat(m.getParameterTypes()[0]).as(m.getName()).isEqualTo(ClientId.class);
        }
    }

    @Test
    @DisplayName("AC-15: no handler parameter carries a jakarta.validation.constraints annotation")
    void noParameterConstraints() {
        for (Method m : handlerMethods()) {
            for (Parameter p : m.getParameters()) {
                for (Annotation a : p.getAnnotations()) {
                    assertThat(a.annotationType().getPackageName()).as("%s %s", m.getName(), p)
                            .isNotEqualTo("jakarta.validation.constraints");
                }
            }
        }
    }
}
