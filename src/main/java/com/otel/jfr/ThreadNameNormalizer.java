package com.otel.jfr;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Collapses variable parts of thread names (pool indices, ids, hashes, request
 * URLs) into {@code #} so per-thread metrics group by thread <em>kind</em>
 * instead of exploding into one series per thread, e.g. {@code pool-3-thread-17}
 * becomes {@code pool#thread#} and {@code http-nio-8080-exec-12} becomes
 * {@code http-nio-#-exec-#}.
 *
 * <p>Results are memoized: thread names repeat constantly and the rules are
 * regex-heavy. The cache is bounded so a pathological stream of unique names
 * cannot grow memory without limit.
 */
final class ThreadNameNormalizer {

  private static final char REPLACEMENT_CHAR = '#';
  private static final String REPLACEMENT_STRING = String.valueOf(REPLACEMENT_CHAR);
  private static final int MAX_CACHE_SIZE = 10_000;

  /** Hex runs of 4+ chars bounded by word breaks, or any run of digits. */
  private static final Pattern DEFAULT_PATTERN =
      Pattern.compile("((?<=[\\W_]|^)([0-9a-fA-F]){4,}(?=[\\W_]|$))|\\d+");

  private static final List<ReplacementRule> RULES =
      Collections.unmodifiableList(
          Arrays.asList(
              constantRegexRule(".* (GET|PUT|POST|DELETE|HEAD) .*", "WebRequest#"),
              constantRegexRule("pool-.*thread.*", "pool#thread#"),
              prefixRule(
                  "ActiveMQ",
                  "BoneCP-keep-alive-scheduler",
                  "CookieBrokerUpdates",
                  "C3P0PooledConnectionPoolManager",
                  "default-akka.actor.default",
                  "DelayedAutomationRunner",
                  "elasticsearch",
                  "hystrix",
                  "org.eclipse.jetty.server.session.HashSessionManager",
                  "jbm-client-session",
                  "JobHandler",
                  "QuartzScheduler",
                  "Sending mailitem",
                  "SOAPProcessorThread",
                  "TransientResourceLock"),
              replaceAfterMatchRule("http:", "https:", "uri:", "@"),
              // many thread names place variables in braces
              enclosingCharsRule('{', '}'),
              enclosingCharsRule('(', ')'),
              enclosingCharsRule('[', ']')));

  private final Map<String, String> cache = new ConcurrentHashMap<>();

  String normalize(String name) {
    if (name == null) {
      return null;
    }
    String cached = cache.get(name);
    if (cached != null) {
      return cached;
    }
    String normalized = compute(name);
    if (cache.size() < MAX_CACHE_SIZE) {
      cache.put(name, normalized);
    }
    return normalized;
  }

  private static String compute(String name) {
    for (ReplacementRule rule : RULES) {
      ReplacementResult result = rule.apply(name);
      if (result != null) {
        name = result.replacement;
        if (result.stop) {
          return name;
        }
      }
    }
    // '#' rather than '*' so backends' metric selectors are not confused;
    // '/' replaced so the name stays a single path segment.
    return DEFAULT_PATTERN.matcher(name).replaceAll(REPLACEMENT_STRING).replace('/', '-');
  }

  private static final class ReplacementResult {
    final boolean stop;
    final String replacement;

    ReplacementResult(boolean stop, String replacement) {
      this.stop = stop;
      this.replacement = replacement;
    }
  }

  private interface ReplacementRule {
    ReplacementResult apply(String name);
  }

  private static ReplacementRule constantRegexRule(String regex, String replacement) {
    Pattern pattern = Pattern.compile(regex);
    ReplacementResult result = new ReplacementResult(true, replacement);
    return name -> pattern.matcher(name).matches() ? result : null;
  }

  /** Matches any one of the terms as a prefix and keeps only that prefix. */
  private static ReplacementRule prefixRule(String... terms) {
    StringJoiner sj = new StringJoiner("|");
    for (String s : terms) {
      sj.add(Pattern.quote(s));
    }
    Pattern pattern = Pattern.compile("(" + sj + ").*");
    return name -> {
      Matcher m = pattern.matcher(name);
      return m.matches() ? new ReplacementResult(true, m.group(1) + REPLACEMENT_CHAR) : null;
    };
  }

  private static ReplacementRule replaceAfterMatchRule(String... patterns) {
    Set<String> all = new HashSet<>(Arrays.asList(patterns));
    for (String p : patterns) {
      all.add(p.toUpperCase(java.util.Locale.ROOT));
    }
    return name -> {
      for (String p : all) {
        int index = name.indexOf(p);
        if (index >= 0) {
          return new ReplacementResult(false, name.substring(0, index) + p + REPLACEMENT_CHAR);
        }
      }
      return null;
    };
  }

  /** Replaces enclosed content, handling nesting such as {@code [[a] [b]]}. */
  private static ReplacementRule enclosingCharsRule(char startChar, char endChar) {
    return name -> {
      int index = 0;
      boolean replaced = false;
      while (index < name.length() && (index = name.indexOf(startChar, index)) >= 0) {
        replaced = true;
        int depth = 1;
        int i = index + 1;
        for (; i < name.length() && depth > 0; i++) {
          if (name.charAt(i) == startChar) {
            depth++;
          } else if (name.charAt(i) == endChar) {
            depth--;
          }
        }
        String replacement = name.substring(0, index) + startChar + REPLACEMENT_CHAR + endChar;
        index = replacement.length();
        name = i == name.length() ? replacement : replacement + name.substring(i);
      }
      return replaced ? new ReplacementResult(false, name) : null;
    };
  }
}
