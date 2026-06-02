package xin.students.imageaioriginal;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
@MapperScan("xin.students.imageaioriginal.mapper")
public class ImageAiOriginalApplication {

    public static void main(String[] args) {
        SpringApplication.run(ImageAiOriginalApplication.class, args);
    }
}
