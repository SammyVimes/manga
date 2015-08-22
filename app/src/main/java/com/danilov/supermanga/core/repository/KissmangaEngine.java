package com.danilov.supermanga.core.repository;

import android.util.Log;

import com.danilov.supermanga.core.http.ExtendedHttpClient;
import com.danilov.supermanga.core.http.HttpBytesReader;
import com.danilov.supermanga.core.http.HttpRequestException;
import com.danilov.supermanga.core.http.HttpStreamModel;
import com.danilov.supermanga.core.http.HttpStreamReader;
import com.danilov.supermanga.core.http.LinesSearchInputStream;
import com.danilov.supermanga.core.model.Manga;
import com.danilov.supermanga.core.model.MangaChapter;
import com.danilov.supermanga.core.model.MangaSuggestion;
import com.danilov.supermanga.core.util.IoUtils;
import com.danilov.supermanga.core.util.ServiceContainer;
import com.danilov.supermanga.core.util.Utils;

import org.apache.http.HttpHost;
import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.protocol.BasicHttpContext;
import org.apache.http.protocol.ExecutionContext;
import org.apache.http.protocol.HTTP;
import org.apache.http.protocol.HttpContext;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Created by Semyon on 03.06.2015.
 */
public class KissmangaEngine implements RepositoryEngine {

    private static final String TAG = "KissmangaEngine";

    private final String baseSearchUri = "http://kissmanga.com/Search/Manga?keyword=";
    public static final String baseUri = "http://kissmanga.com";
    public static final String baseSuggestUri = "http://kissmanga.com/Search/SearchSuggest";

    @Override
    public String getLanguage() {
        return "English";
    }

    private String suggestionPattern = "a href=\"(.*?)\">(.*?)<\\/a>";

    @Override
    public List<MangaSuggestion> getSuggestions(final String query) throws RepositoryException {
        List<MangaSuggestion> mangaSuggestions = new ArrayList<>();
        try {
            DefaultHttpClient httpClient = new ExtendedHttpClient();
            HttpPost request = new HttpPost(baseSuggestUri);
            List<NameValuePair> nameValuePairs = new ArrayList<NameValuePair>(1);
            nameValuePairs.add(new BasicNameValuePair("keyword", query));
            nameValuePairs.add(new BasicNameValuePair("type", "Manga"));
            request.setEntity(new UrlEncodedFormEntity(nameValuePairs));
            HttpResponse response = httpClient.execute(request);
            byte[] result = IoUtils.convertStreamToBytes(response.getEntity().getContent());
            String responseString = IoUtils.convertBytesToString(result);

            Pattern p = Pattern.compile(suggestionPattern);
            Matcher m = p.matcher(responseString);
            while (m.find()) {
                String link = m.group(1);
                int idx = link.indexOf(".com/Manga");
                if (idx != -1) {
                    link = link.substring(idx + 4);
                }
                String title = m.group(2);
                MangaSuggestion suggestion = new MangaSuggestion(title, link, Repository.KISSMANGA);
                mangaSuggestions.add(suggestion);
            }
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        } catch (ClientProtocolException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return mangaSuggestions;
    }

    @Override
    public List<Manga> queryRepository(final String query, final List<Filter.FilterValue> filterValues) {
        List<Manga> mangaList = null;
        try {
            String uri = baseSearchUri + URLEncoder.encode(query, Charset.forName(HTTP.UTF_8).name());
            for (Filter.FilterValue filterValue : filterValues) {
                uri = filterValue.apply(uri);
            }
            DefaultHttpClient httpClient = new ExtendedHttpClient();
            HttpPost request = new HttpPost(uri);

            List<NameValuePair> nameValuePairs = new ArrayList<NameValuePair>(1);
            nameValuePairs.add(new BasicNameValuePair("keyword", query));
            request.setEntity(new UrlEncodedFormEntity(nameValuePairs));

            HttpContext context = new BasicHttpContext();
            HttpResponse response = httpClient.execute(request, context);

            HttpUriRequest currentReq = (HttpUriRequest) context.getAttribute(
                    ExecutionContext.HTTP_REQUEST);
            HttpHost currentHost = (HttpHost)  context.getAttribute(
                    ExecutionContext.HTTP_TARGET_HOST);
            String currentUrl = (currentReq.getURI().isAbsolute()) ? currentReq.getURI().toString() : (currentHost.toURI() + currentReq.getURI());


            byte[] result = IoUtils.convertStreamToBytes(response.getEntity().getContent());
            String responseString = IoUtils.convertBytesToString(result);

            if (currentUrl.contains("kissmanga.com/Manga")) {
                Manga manga = new Manga("", currentUrl, Repository.KISSMANGA);
                parseMangaDescriptionResponse(manga, Utils.toDocument(responseString));
                if (!"".equals(manga.getTitle())) {
                    mangaList = new ArrayList<>(1);
                    mangaList.add(manga);
                }
            } else if (currentUrl.contains("kissmanga.com/Search")) {
                mangaList = parseMangaSearchResponse(Utils.toDocument(responseString));
            }
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        } catch (ClientProtocolException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return mangaList;
    }

    //html values
    private String linkValueAttr = "href";
    private String RESULTS_CLASS = "listing";
    private String RESULT_TAG = "td";

    private final Pattern srcPattern = Pattern.compile("src=\"(.*?)\"");

    private List<Manga> parseMangaSearchResponse(final Document document) {
        List<Manga> mangas = new ArrayList<>();
        Element results = document.getElementsByClass(RESULTS_CLASS).first();
        if (results == null) {
            return mangas;
        }
        Elements mangaResults = results.getElementsByTag(RESULT_TAG);
        int i = 0;
        for (Element td : mangaResults) {
            if (i % 2 == 1) {
                i++;
                continue;
            }
            Element link = td.getElementsByTag("a").first();
            if (link != null) {
                String title = link.text();
                String url = link.attr(linkValueAttr);
                String coverUri = null;

                String titleInner = td.attr("title");
                Matcher matcher = srcPattern.matcher(titleInner);
                if (matcher.find()) {
                    coverUri = matcher.group(1);
                }
                Manga manga = new Manga(title, url, Repository.KISSMANGA);
                manga.setCoverUri(coverUri);
                mangas.add(manga);
            }
            i++;
        }
        return mangas;
    }

    @Override
    public List<Manga> queryRepository(final Genre genre) {
        return Collections.emptyList();
    }

    @Override
    public boolean queryForMangaDescription(final Manga manga) throws RepositoryException {
        HttpBytesReader httpBytesReader = ServiceContainer.getService(HttpBytesReader.class);
        if (httpBytesReader != null) {
            String uri = baseUri + manga.getUri();
            byte[] response = null;
            try {
                response = httpBytesReader.fromUri(uri);
            } catch (HttpRequestException e) {
                if (e.getMessage() != null) {
                    Log.d(TAG, e.getMessage());
                } else {
                    Log.d(TAG, "Failed to load manga description");
                }
                throw new RepositoryException(e.getMessage());
            }
            String responseString = IoUtils.convertBytesToString(response);
            return parseMangaDescriptionResponse(manga, Utils.toDocument(responseString));
        }
        return false;
    }

    private boolean parseMangaDescriptionResponse(final Manga manga, final Document document) {
        try {
            Element titleElement = document.getElementById("leftside").getElementsByClass("barContent").first().getElementsByClass("bigChar").first();
            Element parent = titleElement.parent();
            Element description = parent.child(6);
            manga.setDescription(description.text());
            manga.setTitle(titleElement.text());
            if (manga.getCoverUri() == null || manga.getCoverUri().length() <= 0) {
                Element img = document.getElementById("rightside").getElementsByTag("img").first();
                manga.setCoverUri(img.attr("src"));
            }
            Element results = document.getElementsByClass(RESULTS_CLASS).first();
            int chaptersQuantity = results.getElementsByTag("td").size() / 2;
            manga.setChaptersQuantity(chaptersQuantity);
            return true;
        } catch (Exception e) {

        }

        return false;
    }

    @Override
    public boolean queryForChapters(final Manga manga) throws RepositoryException {
        HttpBytesReader httpBytesReader = ServiceContainer.getService(HttpBytesReader.class);
        if (httpBytesReader != null) {
            String uri = baseUri + manga.getUri();
            byte[] response = null;
            try {
                response = httpBytesReader.fromUri(uri);
            } catch (HttpRequestException e) {
                Log.d(TAG, e.getMessage());
                throw new RepositoryException(e.getMessage());
            }
            String responseString = IoUtils.convertBytesToString(response);
            List<MangaChapter> chapters = parseMangaChaptersResponse(Utils.toDocument(responseString));
            if (chapters != null) {
                manga.setChapters(chapters);
                return true;
            }
        }
        return false;
    }

    private List<MangaChapter> parseMangaChaptersResponse(final Document document) {
        List<MangaChapter> mangaChapters = null;
        try {
            Element results = document.getElementsByClass(RESULTS_CLASS).first();
            Elements as = results.getElementsByTag("a");
            int chaptersQuantity = as.size();
            mangaChapters = new ArrayList<>(chaptersQuantity);
            int number = 0;
            for (int i = as.size() - 1; i >= 0; i--) {
                Element link = as.get(i);
                MangaChapter chapter = new MangaChapter(link.text(), number, link.attr(linkValueAttr));
                mangaChapters.add(chapter);
                number++;
            }
            return mangaChapters;
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    @Override
    public List<String> getChapterImages(final MangaChapter chapter) throws RepositoryException {
        String uri = baseUri + chapter.getUri();
        HttpStreamReader httpStreamReader = ServiceContainer.getService(HttpStreamReader.class);
        byte[] bytes = new byte[1024];
        List<String> imageUrls = null;
        LinesSearchInputStream inputStream = null;
        try {
            HttpStreamModel model = httpStreamReader.fromUri(uri);
            inputStream = new LinesSearchInputStream(model.stream, "var lstImages = new Array();", " var lstImagesLoaded = new Array();");
            int status = LinesSearchInputStream.SEARCHING;
            while (status == LinesSearchInputStream.SEARCHING) {
                status = inputStream.read(bytes);
            }
            bytes = inputStream.getResult();
            String str = IoUtils.convertBytesToString(bytes);
            imageUrls = extractUrls(str);
        } catch (IOException e) {
            Log.d(TAG, e.getMessage());
            throw new RepositoryException(e.getMessage());
        } catch (HttpRequestException e) {
            Log.d(TAG, e.getMessage());
            throw new RepositoryException(e.getMessage());
        } finally {
            if (inputStream != null) {
                try {
                    inputStream.close();
                } catch (IOException e) {
                    Log.d(TAG, e.getMessage());
                }
            }
        }
        return imageUrls;
    }

    private final Pattern urlPattern = Pattern.compile("\"(.*?)\"");

    private List<String> extractUrls(final String str) {
        Log.d(TAG, "a: " + str);
        Matcher matcher = urlPattern.matcher(str);
        List<String> urls = new ArrayList<String>();
        while (matcher.find()) {
            String url = matcher.group(1);
            if (!url.contains("http")) {
                url = baseUri + url;
            }
            urls.add(url);
        }
        return urls;
    }

    @Override
    public String getBaseSearchUri() {
        return null;
    }

    @Override
    public String getBaseUri() {
        return null;
    }

    @Override
    public List<FilterGroup> getFilters() {
        return Collections.emptyList();
    }

    @Override
    public List<Genre> getGenres() {
        return Collections.emptyList();
    }

}