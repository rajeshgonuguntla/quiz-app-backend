package com.codapt.quizapp.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class TraceCorrelationFilter extends OncePerRequestFilter {

    private static final String TRACE_HEADER = "X-Cloud-Trace-Context";
    private static final String TRACE_ID_MDC = "logging.googleapis.com/trace";
    private static final String SPAN_ID_MDC = "logging.googleapis.com/spanId";
    private static final String TRACE_SAMPLED_MDC = "logging.googleapis.com/trace_sampled";

    private final String gcpProjectId;

    public TraceCorrelationFilter(@Value("${gcp.project-id:${GOOGLE_CLOUD_PROJECT:}}") String gcpProjectId) {
        this.gcpProjectId = gcpProjectId;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String traceHeader = request.getHeader(TRACE_HEADER);

        try {
            putTraceContext(traceHeader);
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(TRACE_ID_MDC);
            MDC.remove(SPAN_ID_MDC);
            MDC.remove(TRACE_SAMPLED_MDC);
        }
    }

    private void putTraceContext(String traceHeader) {
        if (traceHeader == null || traceHeader.isBlank()) {
            return;
        }

        String[] headerParts = traceHeader.split(";");
        String[] traceAndSpan = headerParts[0].split("/", 2);

        String traceId = traceAndSpan[0];
        if (!traceId.isBlank() && !gcpProjectId.isBlank()) {
            MDC.put(TRACE_ID_MDC, "projects/" + gcpProjectId + "/traces/" + traceId);
        }

        if (traceAndSpan.length > 1 && !traceAndSpan[1].isBlank()) {
            MDC.put(SPAN_ID_MDC, traceAndSpan[1]);
        }

        if (headerParts.length > 1 && headerParts[1].startsWith("o=")) {
            String sampledFlag = headerParts[1].substring(2).trim();
            MDC.put(TRACE_SAMPLED_MDC, String.valueOf("1".equals(sampledFlag)));
        }
    }
}

