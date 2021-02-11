/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.lucene.analysis.hunspell;

import static org.apache.lucene.analysis.hunspell.Dictionary.AFFIX_APPEND;
import static org.apache.lucene.analysis.hunspell.Dictionary.AFFIX_FLAG;
import static org.apache.lucene.analysis.hunspell.Dictionary.AFFIX_STRIP_ORD;

import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;
import org.apache.lucene.util.IntsRef;
import org.apache.lucene.util.fst.FST;
import org.apache.lucene.util.fst.IntsRefFSTEnum;

/**
 * A class that traverses the entire dictionary and applies affix rules to check if those yield
 * correct suggestions similar enough to the given misspelled word
 */
class GeneratingSuggester {
  private static final int MAX_ROOTS = 100;
  private static final int MAX_WORDS = 100;
  private static final int MAX_GUESSES = 200;
  private final Dictionary dictionary;
  private final SpellChecker speller;

  GeneratingSuggester(SpellChecker speller) {
    this.dictionary = speller.dictionary;
    this.speller = speller;
  }

  List<String> suggest(String word, WordCase originalCase, Set<String> prevSuggestions) {
    List<Weighted<DictEntry>> roots = findSimilarDictionaryEntries(word, originalCase);
    List<Weighted<String>> expanded = expandRoots(word, roots);
    TreeSet<Weighted<String>> bySimilarity = rankBySimilarity(word, expanded);
    return getMostRelevantSuggestions(bySimilarity, prevSuggestions);
  }

  private List<Weighted<DictEntry>> findSimilarDictionaryEntries(
      String word, WordCase originalCase) {
    TreeSet<Weighted<DictEntry>> roots = new TreeSet<>();
    processFST(
        dictionary.words,
        (key, forms) -> {
          if (Math.abs(key.length - word.length()) > 4) return;

          String root = toString(key);
          List<DictEntry> entries = filterSuitableEntries(root, forms);
          if (entries.isEmpty()) return;

          if (originalCase == WordCase.LOWER
              && WordCase.caseOf(root) == WordCase.TITLE
              && !dictionary.hasLanguage("de")) {
            return;
          }

          String lower = dictionary.toLowerCase(root);
          int sc =
              ngram(3, word, lower, EnumSet.of(NGramOptions.LONGER_WORSE))
                  + commonPrefix(word, root);

          entries.forEach(e -> roots.add(new Weighted<>(e, sc)));
        });
    return roots.stream().limit(MAX_ROOTS).collect(Collectors.toList());
  }

  private void processFST(FST<IntsRef> fst, BiConsumer<IntsRef, IntsRef> keyValueConsumer) {
    if (fst == null) return;
    try {
      IntsRefFSTEnum<IntsRef> fstEnum = new IntsRefFSTEnum<>(fst);
      IntsRefFSTEnum.InputOutput<IntsRef> mapping;
      while ((mapping = fstEnum.next()) != null) {
        keyValueConsumer.accept(mapping.input, mapping.output);
      }
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private static String toString(IntsRef key) {
    char[] chars = new char[key.length];
    for (int i = 0; i < key.length; i++) {
      chars[i] = (char) key.ints[i + key.offset];
    }
    return new String(chars);
  }

  private List<DictEntry> filterSuitableEntries(String word, IntsRef forms) {
    List<DictEntry> result = new ArrayList<>();
    for (int i = 0; i < forms.length; i += dictionary.formStep()) {
      int entryId = forms.ints[forms.offset + i];
      if (dictionary.hasFlag(entryId, dictionary.forbiddenword)
          || dictionary.hasFlag(entryId, dictionary.noSuggest)
          || dictionary.hasFlag(entryId, Dictionary.HIDDEN_FLAG)
          || dictionary.hasFlag(entryId, dictionary.onlyincompound)) {
        continue;
      }
      result.add(new DictEntry(word, entryId));
    }

    return result;
  }

  private List<Weighted<String>> expandRoots(String misspelled, List<Weighted<DictEntry>> roots) {
    int thresh = calcThreshold(misspelled);

    TreeSet<Weighted<String>> expanded = new TreeSet<>();
    for (Weighted<DictEntry> weighted : roots) {
      for (String guess : expandRoot(weighted.word, misspelled)) {
        String lower = dictionary.toLowerCase(guess);
        int sc =
            ngram(misspelled.length(), misspelled, lower, EnumSet.of(NGramOptions.ANY_MISMATCH))
                + commonPrefix(misspelled, guess);
        if (sc > thresh) {
          expanded.add(new Weighted<>(guess, sc));
        }
      }
    }
    return expanded.stream().limit(MAX_GUESSES).collect(Collectors.toList());
  }

  // find minimum threshold for a passable suggestion
  // mangle original word three different ways
  // and score them to generate a minimum acceptable score
  private static int calcThreshold(String word) {
    int thresh = 0;
    for (int sp = 1; sp < 4; sp++) {
      char[] mw = word.toCharArray();
      for (int k = sp; k < word.length(); k += 4) {
        mw[k] = '*';
      }

      thresh += ngram(word.length(), word, new String(mw), EnumSet.of(NGramOptions.ANY_MISMATCH));
    }
    return thresh / 3 - 1;
  }

  private List<String> expandRoot(DictEntry root, String misspelled) {
    List<String> crossProducts = new ArrayList<>();
    Set<String> result = new LinkedHashSet<>();

    if (!dictionary.hasFlag(root.entryId, dictionary.needaffix)) {
      result.add(root.word);
    }

    // suffixes
    processFST(
        dictionary.suffixes,
        (key, ids) -> {
          String suffix = new StringBuilder(toString(key)).reverse().toString();
          if (misspelled.length() <= suffix.length() || !misspelled.endsWith(suffix)) return;

          for (int i = 0; i < ids.length; i++) {
            int suffixId = ids.ints[ids.offset + i];
            if (!hasCompatibleFlags(root, suffixId) || !checkAffixCondition(suffixId, root.word)) {
              continue;
            }

            String withSuffix =
                root.word.substring(0, root.word.length() - affixStripLength(suffixId)) + suffix;
            result.add(withSuffix);
            if (dictionary.isCrossProduct(suffixId)) {
              crossProducts.add(withSuffix);
            }
          }
        });

    // cross-product prefixes
    processFST(
        dictionary.prefixes,
        (key, ids) -> {
          String prefix = toString(key);
          if (misspelled.length() <= prefix.length() || !misspelled.startsWith(prefix)) return;

          for (int i = 0; i < ids.length; i++) {
            int prefixId = ids.ints[ids.offset + i];
            if (!dictionary.hasFlag(root.entryId, dictionary.affixData(prefixId, AFFIX_FLAG))
                || !dictionary.isCrossProduct(prefixId)) {
              continue;
            }

            for (String suffixed : crossProducts) {
              if (checkAffixCondition(prefixId, suffixed)) {
                result.add(prefix + suffixed.substring(affixStripLength(prefixId)));
              }
            }
          }
        });

    // pure prefixes
    processFST(
        dictionary.prefixes,
        (key, ids) -> {
          String prefix = toString(key);
          if (misspelled.length() <= prefix.length() || !misspelled.startsWith(prefix)) return;

          for (int i = 0; i < ids.length; i++) {
            int prefixId = ids.ints[ids.offset + i];
            if (hasCompatibleFlags(root, prefixId) && checkAffixCondition(prefixId, root.word)) {
              result.add(prefix + root.word.substring(affixStripLength(prefixId)));
            }
          }
        });

    return result.stream().limit(MAX_WORDS).collect(Collectors.toList());
  }

  private boolean hasCompatibleFlags(DictEntry root, int affixId) {
    if (!dictionary.hasFlag(root.entryId, dictionary.affixData(affixId, AFFIX_FLAG))) {
      return false;
    }

    int append = dictionary.affixData(affixId, AFFIX_APPEND);
    return !dictionary.hasFlag(append, dictionary.needaffix)
        && !dictionary.hasFlag(append, dictionary.circumfix)
        && !dictionary.hasFlag(append, dictionary.onlyincompound);
  }

  private boolean checkAffixCondition(int suffixId, String stem) {
    int condition = dictionary.getAffixCondition(suffixId);
    return condition == 0 || dictionary.patterns.get(condition).run(stem);
  }

  private int affixStripLength(int affixId) {
    char stripOrd = dictionary.affixData(affixId, AFFIX_STRIP_ORD);
    return dictionary.stripOffsets[stripOrd + 1] - dictionary.stripOffsets[stripOrd];
  }

  private TreeSet<Weighted<String>> rankBySimilarity(String word, List<Weighted<String>> expanded) {
    double fact = (10.0 - dictionary.maxDiff) / 5.0;
    TreeSet<Weighted<String>> bySimilarity = new TreeSet<>();
    for (Weighted<String> weighted : expanded) {
      String guess = weighted.word;
      String lower = dictionary.toLowerCase(guess);
      if (lower.equals(word)) {
        bySimilarity.add(new Weighted<>(guess, weighted.score + 2000));
        break;
      }

      int re =
          ngram(2, word, lower, EnumSet.of(NGramOptions.ANY_MISMATCH, NGramOptions.WEIGHTED))
              + ngram(2, lower, word, EnumSet.of(NGramOptions.ANY_MISMATCH, NGramOptions.WEIGHTED));

      int score =
          2 * lcs(word, lower)
              - Math.abs(word.length() - lower.length())
              + commonCharacterPositionScore(word, lower)
              + commonPrefix(word, lower)
              + ngram(4, word, lower, EnumSet.of(NGramOptions.ANY_MISMATCH))
              + re
              + (re < (word.length() + lower.length()) * fact ? -1000 : 0);
      bySimilarity.add(new Weighted<>(guess, score));
    }
    return bySimilarity;
  }

  private List<String> getMostRelevantSuggestions(
      TreeSet<Weighted<String>> bySimilarity, Set<String> prevSuggestions) {
    List<String> result = new ArrayList<>();
    boolean hasExcellent = false;
    for (Weighted<String> weighted : bySimilarity) {
      if (weighted.score > 1000) {
        hasExcellent = true;
      } else if (hasExcellent) {
        break; // leave only excellent suggestions, if any
      }

      boolean bad = weighted.score < -100;
      // keep the best ngram suggestions, unless in ONLYMAXDIFF mode
      if (bad && (!result.isEmpty() || dictionary.onlyMaxDiff)) {
        break;
      }

      if (prevSuggestions.stream().noneMatch(weighted.word::contains)
          && result.stream().noneMatch(weighted.word::contains)
          && speller.checkWord(weighted.word)) {
        result.add(weighted.word);
        if (result.size() > dictionary.maxNGramSuggestions) {
          break;
        }
      }

      if (bad) {
        break;
      }
    }
    return result;
  }

  private static int commonPrefix(String s1, String s2) {
    int i = 0;
    int limit = Math.min(s1.length(), s2.length());
    while (i < limit && s1.charAt(i) == s2.charAt(i)) {
      i++;
    }
    return i;
  }

  // generate an n-gram score comparing s1 and s2
  private static int ngram(int n, String s1, String s2, EnumSet<NGramOptions> opt) {
    int score = 0;
    int l1 = s1.length();
    int l2 = s2.length();
    if (l2 == 0) {
      return 0;
    }
    for (int j = 1; j <= n; j++) {
      int ns = 0;
      for (int i = 0; i <= (l1 - j); i++) {
        if (s2.contains(s1.substring(i, i + j))) {
          ns++;
        } else if (opt.contains(NGramOptions.WEIGHTED)) {
          ns--;
          if (i == 0 || i == l1 - j) {
            ns--; // side weight
          }
        }
      }
      score = score + ns;
      if (ns < 2 && !opt.contains(NGramOptions.WEIGHTED)) {
        break;
      }
    }

    int ns = 0;
    if (opt.contains(NGramOptions.LONGER_WORSE)) {
      ns = (l2 - l1) - 2;
    }
    if (opt.contains(NGramOptions.ANY_MISMATCH)) {
      ns = Math.abs(l2 - l1) - 2;
    }
    return score - Math.max(ns, 0);
  }

  private static int lcs(String s1, String s2) {
    int[] lengths = new int[s2.length() + 1];

    for (int i = 1; i <= s1.length(); i++) {
      int prev = 0;
      for (int j = 1; j <= s2.length(); j++) {
        int cur = lengths[j];
        lengths[j] =
            s1.charAt(i - 1) == s2.charAt(j - 1) ? prev + 1 : Math.max(cur, lengths[j - 1]);
        prev = cur;
      }
    }
    return lengths[s2.length()];
  }

  private static int commonCharacterPositionScore(String s1, String s2) {
    int num = 0;
    int diffPos1 = -1;
    int diffPos2 = -1;
    int diff = 0;
    int i;
    for (i = 0; i < s1.length() && i < s2.length(); ++i) {
      if (s1.charAt(i) == s2.charAt(i)) {
        num++;
      } else {
        if (diff == 0) diffPos1 = i;
        else if (diff == 1) diffPos2 = i;
        diff++;
      }
    }
    int commonScore = num > 0 ? 1 : 0;
    if (diff == 2
        && i == s1.length()
        && i == s2.length()
        && s1.charAt(diffPos1) == s2.charAt(diffPos2)
        && s1.charAt(diffPos2) == s2.charAt(diffPos1)) {
      return commonScore + 10;
    }
    return commonScore;
  }

  private enum NGramOptions {
    WEIGHTED,
    LONGER_WORSE,
    ANY_MISMATCH
  }

  private static class Weighted<T extends Comparable<T>> implements Comparable<Weighted<T>> {
    final T word;
    final int score;

    Weighted(T word, int score) {
      this.word = word;
      this.score = score;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (!(o instanceof Weighted)) return false;
      @SuppressWarnings("unchecked")
      Weighted<T> that = (Weighted<T>) o;
      return score == that.score && word.equals(that.word);
    }

    @Override
    public int hashCode() {
      return Objects.hash(word, score);
    }

    @Override
    public String toString() {
      return word + "(" + score + ")";
    }

    @Override
    public int compareTo(Weighted<T> o) {
      int cmp = Integer.compare(score, o.score);
      return cmp != 0 ? -cmp : word.compareTo(o.word);
    }
  }

  private static class DictEntry implements Comparable<DictEntry> {
    private final String word;
    private final int entryId;

    DictEntry(String word, int entryId) {
      this.word = word;
      this.entryId = entryId;
    }

    @Override
    public String toString() {
      return word;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (!(o instanceof DictEntry)) return false;
      DictEntry dictEntry = (DictEntry) o;
      return entryId == dictEntry.entryId && word.equals(dictEntry.word);
    }

    @Override
    public int hashCode() {
      return Objects.hash(word, entryId);
    }

    @Override
    public int compareTo(DictEntry o) {
      return word.compareTo(o.word);
    }
  }
}