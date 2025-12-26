package com.pvmanagement.demo;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class DemoServiceTest {

    @Test
    void sum_returnsSum() {
        DemoService service = new DemoService();
        int result = service.sum(1, 2);
        assertThat(result).isEqualTo(3);
    }
}
