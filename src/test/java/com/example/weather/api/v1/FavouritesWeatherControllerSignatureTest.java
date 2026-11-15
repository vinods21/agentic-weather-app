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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/** Unit test (reflection): the ADR 0009/0010 handler-signature rules for the new controller. No Spring, no Docker. */
class FavouritesWeatherControllerSignatureTest {

    private static List<Method> handlerMethods() {
        return Arrays.stream(FavouritesWeatherController.class.getDeclaredMethods())
                .filter(m -> AnnotatedElementUtils.hasAnnotation(m, RequestMapping.class))
                .toList();
    }

    @Test
    @DisplayName("AC-5: the single handler is GET /weather and takes ClientId as its first parameter")
    void clientIdFirst() {
        assertThat(handlerMethods()).hasSize(1);
        Method m = handlerMethods().get(0);
        assertThat(m.getParameterTypes()[0]).isEqualTo(ClientId.class);
        assertThat(m.getAnnotation(GetMapping.class).value()).containsExactly("/weather");
        assertThat(FavouritesWeatherController.class.getAnnotation(RequestMapping.class).value())
                .containsExactly("/api/v1/favourites");
    }

    @Test
    @DisplayName("AC-5: no handler parameter carries a jakarta.validation.constraints annotation")
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
