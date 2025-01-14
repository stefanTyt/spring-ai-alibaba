package com.alibaba.cloud.ai.reader.arxiv.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * arXiv API客户端，用于获取搜索结果
 *
 * @author brianxiadong
 */
public class ArxivClient {

	private static final Logger logger = LoggerFactory.getLogger(ArxivClient.class);

	private static final String QUERY_URL_FORMAT = "https://export.arxiv.org/api/query?%s";

	private static final DateTimeFormatter ATOM_DATE_FORMAT = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

	private final int pageSize; // 单次API请求的最大结果数

	private final float delaySeconds; // API请求之间的延迟秒数

	private final int numRetries; // 失败重试次数

	private LocalDateTime lastRequestTime; // 上次请求时间

	private final HttpClient httpClient;

	private final DocumentBuilder documentBuilder;

	public ArxivClient() {
		this(100, 3.0f, 3);
	}

	public ArxivClient(int pageSize, float delaySeconds, int numRetries) {
		this.pageSize = pageSize;
		this.delaySeconds = delaySeconds;
		this.numRetries = numRetries;
		this.httpClient = HttpClient.newBuilder()
			.version(HttpClient.Version.HTTP_2)
			.connectTimeout(Duration.ofSeconds(10))
			.build();

		try {
			DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
			factory.setNamespaceAware(true);
			this.documentBuilder = factory.newDocumentBuilder();
		}
		catch (Exception e) {
			throw new RuntimeException("Failed to initialize XML parser", e);
		}
	}

	/**
	 * 执行搜索并返回结果迭代器
	 */
	public Iterator<ArxivResult> results(ArxivSearch search, int offset) throws IOException {
		if (search.getMaxResults() != null) {
			int limit = search.getMaxResults() - offset;
			if (limit < 0) {
				return Collections.emptyIterator();
			}
		}
		return new ResultIterator(search, offset);
	}

	/**
	 * 构造请求URL
	 */
	private String formatUrl(ArxivSearch search, int start, int pageSize) {
		Map<String, String> urlArgs = search.getUrlArgs();
		urlArgs.put("start", String.valueOf(start));
		urlArgs.put("max_results", String.valueOf(pageSize));

		String queryString = urlArgs.entrySet()
			.stream()
			.map(e -> String.format("%s=%s", URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8),
					URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8)))
			.collect(Collectors.joining("&"));

		return String.format(QUERY_URL_FORMAT, queryString);
	}

	/**
	 * 解析feed并返回结果
	 */
	private Document parseFeed(String url, boolean firstPage, int tryIndex) throws IOException {
		try {
			return tryParseFeed(url, firstPage, tryIndex);
		}
		catch (Exception e) {
			if (tryIndex < numRetries) {
				logger.debug("Got error (try {}): {}", tryIndex, e.getMessage());
				return parseFeed(url, firstPage, tryIndex + 1);
			}
			logger.debug("Giving up (try {}): {}", tryIndex, e.getMessage());
			throw new IOException("Failed to parse feed after " + numRetries + " retries", e);
		}
	}

	/**
	 * 尝试解析feed
	 */
	private Document tryParseFeed(String url, boolean firstPage, int tryIndex) throws Exception {
		// 检查是否需要等待
		if (lastRequestTime != null) {
			long sinceLastRequest = java.time.Duration.between(lastRequestTime, LocalDateTime.now()).toMillis();
			long requiredDelay = (long) (delaySeconds * 1000);
			if (sinceLastRequest < requiredDelay) {
				Thread.sleep(requiredDelay - sinceLastRequest);
			}
		}

		logger.info("Requesting page (first: {}, try: {}): {}", firstPage, tryIndex, url);

		HttpRequest request = HttpRequest.newBuilder()
			.uri(URI.create(url))
			.header("User-Agent", "arxiv-java-client/1.0.0")
			.GET()
			.build();

		HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
		lastRequestTime = LocalDateTime.now();

		if (response.statusCode() != 200) {
			throw new IOException("HTTP " + response.statusCode());
		}

		Document doc = documentBuilder
			.parse(new ByteArrayInputStream(response.body().getBytes(StandardCharsets.UTF_8)));
		NodeList entries = doc.getElementsByTagName("entry");

		if (entries.getLength() == 0 && !firstPage) {
			throw new IOException("Unexpected empty page");
		}

		return doc;
	}

	/**
	 * 从XML条目创建ArxivResult对象
	 */
	private ArxivResult resultFromEntry(Element entry) {
		ArxivResult result = new ArxivResult();

		// 设置基本字段
		result.setEntryId(getElementText(entry, "id"));
		result.setTitle(getElementText(entry, "title").replaceAll("\\s+", " "));
		result.setSummary(getElementText(entry, "summary"));

		// 设置日期
		String updated = getElementText(entry, "updated");
		String published = getElementText(entry, "published");
		result.setUpdated(LocalDateTime.parse(updated, ATOM_DATE_FORMAT));
		result.setPublished(LocalDateTime.parse(published, ATOM_DATE_FORMAT));

		// 设置作者
		NodeList authors = entry.getElementsByTagName("author");
		result.setAuthors(IntStream.range(0, authors.getLength())
			.mapToObj(i -> authors.item(i))
			.map(node -> new ArxivResult.ArxivAuthor(getElementText((Element) node, "name")))
			.collect(Collectors.toList()));

		// 设置分类
		NodeList categories = entry.getElementsByTagName("category");
		result.setCategories(IntStream.range(0, categories.getLength())
			.mapToObj(i -> categories.item(i))
			.map(node -> ((Element) node).getAttribute("term"))
			.collect(Collectors.toList()));

		// 设置主分类
		NodeList primaryCategory = entry.getElementsByTagName("arxiv:primary_category");
		if (primaryCategory.getLength() > 0) {
			result.setPrimaryCategory(((Element) primaryCategory.item(0)).getAttribute("term"));
		}

		// 设置链接
		NodeList links = entry.getElementsByTagName("link");
		result.setLinks(IntStream.range(0, links.getLength()).mapToObj(i -> links.item(i)).map(node -> {
			Element link = (Element) node;
			return new ArxivResult.ArxivLink(link.getAttribute("href"), link.getAttribute("title"),
					link.getAttribute("rel"), link.getAttribute("type"));
		}).collect(Collectors.toList()));

		// 设置其他可选字段
		Optional.ofNullable(getElementText(entry, "arxiv:comment")).ifPresent(result::setComment);
		Optional.ofNullable(getElementText(entry, "arxiv:journal_ref")).ifPresent(result::setJournalRef);
		Optional.ofNullable(getElementText(entry, "arxiv:doi")).ifPresent(result::setDoi);

		return result;
	}

	/**
	 * 获取XML元素的文本内容
	 */
	private String getElementText(Element parent, String tagName) {
		NodeList nodes = parent.getElementsByTagName(tagName);
		if (nodes.getLength() == 0) {
			return null;
		}
		return nodes.item(0).getTextContent();
	}

	/**
	 * 结果迭代器内部类
	 */
	private class ResultIterator implements Iterator<ArxivResult> {

		private final ArxivSearch search;

		private int offset;

		private NodeList currentPage;

		private int currentIndex;

		private int totalResults;

		private int returnedResults; // 添加计数器跟踪已返回的结果数量

		public ResultIterator(ArxivSearch search, int offset) throws IOException {
			this.search = search;
			this.offset = offset;
			this.currentIndex = 0;
			this.returnedResults = 0; // 初始化计数器
			fetchNextPage(true);
		}

		@Override
		public boolean hasNext() {
			// 检查是否达到最大结果数限制
			if (search.getMaxResults() != null && returnedResults >= search.getMaxResults()) {
				return false;
			}
			return currentPage != null && (currentIndex < currentPage.getLength() || offset < totalResults);
		}

		@Override
		public ArxivResult next() {
			if (!hasNext()) {
				throw new NoSuchElementException();
			}

			if (currentIndex >= currentPage.getLength()) {
				try {
					fetchNextPage(false);
					currentIndex = 0;
				}
				catch (IOException e) {
					throw new RuntimeException("Failed to fetch next page", e);
				}
			}

			returnedResults++; // 增加计数器
			return resultFromEntry((Element) currentPage.item(currentIndex++));
		}

		private void fetchNextPage(boolean firstPage) throws IOException {
			// 如果设置了maxResults，调整pageSize以避免获取过多结果
			int adjustedPageSize = pageSize;
			if (search.getMaxResults() != null) {
				int remaining = search.getMaxResults() - returnedResults;
				adjustedPageSize = Math.min(pageSize, remaining);
				if (adjustedPageSize <= 0) {
					currentPage = null;
					return;
				}
			}

			String url = formatUrl(search, offset, adjustedPageSize);
			Document doc = parseFeed(url, firstPage, 0);

			if (firstPage) {
				NodeList totalResultsNode = doc.getElementsByTagName("opensearch:totalResults");
				totalResults = Integer.parseInt(totalResultsNode.item(0).getTextContent());
				// 如果设置了maxResults，调整totalResults
				if (search.getMaxResults() != null) {
					totalResults = Math.min(totalResults, search.getMaxResults());
				}
				logger.info("Got first page: {} entries of {} total results (max: {})",
						doc.getElementsByTagName("entry").getLength(), totalResults,
						search.getMaxResults() != null ? search.getMaxResults() : "unlimited");
			}

			currentPage = doc.getElementsByTagName("entry");
			offset += currentPage.getLength();
		}

	}

	/**
	 * 下载论文的PDF文件
	 * @param result arXiv搜索结果
	 * @param dirPath 保存目录的路径
	 * @param filename 可选的文件名，如果为null则使用默认文件名
	 * @return 保存的文件路径
	 * @throws IOException 如果下载或保存过程中发生错误
	 */
	public Path downloadPdf(ArxivResult result, String dirPath, String filename) throws IOException {
		if (result.getPdfUrl() == null) {
			throw new IOException("PDF URL not available for this result");
		}

		// 创建保存目录
		Path dir = Paths.get(dirPath);
		if (!Files.exists(dir)) {
			Files.createDirectories(dir);
		}

		// 确定文件名
		String actualFilename = filename != null ? filename : result.getDefaultFilename("pdf");
		Path targetPath = dir.resolve(actualFilename);

		// 构建下载请求
		HttpRequest request = HttpRequest.newBuilder()
			.uri(URI.create(result.getPdfUrl()))
			.header("User-Agent", "arxiv-java-client/1.0.0")
			.GET()
			.build();

		// 执行下载
		try {
			// 检查是否需要等待
			if (lastRequestTime != null) {
				long sinceLastRequest = java.time.Duration.between(lastRequestTime, LocalDateTime.now()).toMillis();
				long requiredDelay = (long) (delaySeconds * 1000);
				if (sinceLastRequest < requiredDelay) {
					Thread.sleep(requiredDelay - sinceLastRequest);
				}
			}

			logger.info("Downloading PDF: {}", result.getPdfUrl());

			HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
			lastRequestTime = LocalDateTime.now();

			if (response.statusCode() != 200) {
				throw new IOException("Failed to download PDF: HTTP " + response.statusCode());
			}

			// 保存文件
			try (InputStream in = response.body()) {
				Files.copy(in, targetPath, StandardCopyOption.REPLACE_EXISTING);
			}

			logger.info("PDF saved to: {}", targetPath);
			return targetPath;

		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("Download interrupted", e);
		}
	}

	/**
	 * 下载论文的PDF文件（使用默认文件名）
	 * @param result arXiv搜索结果
	 * @param dirPath 保存目录的路径
	 * @return 保存的文件路径
	 * @throws IOException 如果下载或保存过程中发生错误
	 */
	public Path downloadPdf(ArxivResult result, String dirPath) throws IOException {
		return downloadPdf(result, dirPath, null);
	}

}