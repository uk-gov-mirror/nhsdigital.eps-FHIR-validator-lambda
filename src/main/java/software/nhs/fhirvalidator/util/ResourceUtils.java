package software.nhs.fhirvalidator.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ResourceUtils {
    static Logger log = LoggerFactory.getLogger(ResourceUtils.class);

    private ResourceUtils() {
        throw new IllegalStateException("Utility class");
    }

    public static String getResourceContent(String resource) {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        try {
            ByteArrayOutputStream result;
            try (InputStream inputStream = loader.getResourceAsStream(resource)) {
                result = new ByteArrayOutputStream();
                byte[] buffer = new byte[1024];

                for (int length; (length = inputStream.read(buffer)) != -1;) {
                    result.write(buffer, 0, length);
                }
            }
            return result.toString("UTF-8");

        } catch (IOException ex) {
            log.error(ex.getMessage(), ex);
            throw new RuntimeException("error in getResourceContent", ex);
        }
    }

}
