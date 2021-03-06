package org.kafkahq.controllers;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.inject.Inject;
import org.codehaus.httpcache4j.uri.QueryParams;
import org.codehaus.httpcache4j.uri.URIBuilder;
import org.jooby.Request;
import org.jooby.Result;
import org.jooby.Results;
import org.jooby.View;
import org.jooby.mvc.GET;
import org.jooby.mvc.Path;
import org.kafkahq.models.Config;
import org.kafkahq.models.Record;
import org.kafkahq.models.Topic;
import org.kafkahq.modules.RequestHelper;
import org.kafkahq.repositories.ConfigRepository;
import org.kafkahq.repositories.RecordRepository;
import org.kafkahq.repositories.TopicRepository;
import org.kafkahq.response.ResultStatusResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;

@Path("/{cluster}/topic")
public class TopicController extends AbstractController {
    private static final Logger logger = LoggerFactory.getLogger(TopicController.class);

    @Inject
    private TopicRepository topicRepository;

    @Inject
    private ConfigRepository configRepository;

    @Inject
    private RecordRepository recordRepository;

    @GET
    public View list(Request request) throws ExecutionException, InterruptedException {
        return this.template(
            request,
            Results
                .html("topicList")
                .put("topics", this.topicRepository.list())
        );
    }

    @GET
    @Path("{topic}")
    public View home(Request request) throws ExecutionException, InterruptedException {
        Topic topic = this.topicRepository.findByName(request.param("topic").value());
        RecordRepository.Options options = RequestHelper.buildRecordRepositoryOptions(request);

        List<Record<String, String>> data = new ArrayList<>();

        if (options.getSearch() == null) {
            data = this.recordRepository.consume(options);
        }

        URIBuilder uri = URIBuilder.empty()
            .withPath(request.path())
            .withParameters(QueryParams.parse(request.queryString().orElse("")));

        ImmutableMap.Builder<String, String> partitionUrls = ImmutableSortedMap.naturalOrder();
        partitionUrls.put((uri.getParametersByName("partition").size() > 0 ? uri.removeParameters("partition") : uri).toNormalizedURI(false).toString(), "All");
        for (int i = 0; i < topic.getPartitions().size(); i++) {
            partitionUrls.put(uri.addParameter("partition", String.valueOf(i)).toNormalizedURI(false).toString(), String.valueOf(i));
        }

        return this.template(
            request,
            Results
                .html("topic")
                .put("tab", "data")
                .put("topic", topic)
                .put("canDeleteRecords", topic.canDeleteRecords(configRepository))
                .put("datas", data)
                .put("navbar", ImmutableMap.builder()
                    .put("partition", ImmutableMap.builder()
                        .put("current", Optional.ofNullable(options.getPartition()))
                        .put("values", partitionUrls.build())
                        .build()
                    )
                    .put("sort", ImmutableMap.builder()
                        .put("current", Optional.ofNullable(options.getSort()))
                        .put("values", ImmutableMap.builder()
                            .put(uri.addParameter("sort", RecordRepository.Options.Sort.NEWEST.name()).toNormalizedURI(false).toString(), RecordRepository.Options.Sort.NEWEST.name())
                            .put(uri.addParameter("sort", RecordRepository.Options.Sort.OLDEST.name()).toNormalizedURI(false).toString(), RecordRepository.Options.Sort.OLDEST.name())
                            .build()
                        )
                        .build()
                    )
                    .put("timestamp", ImmutableMap.builder()
                        .put("current", Optional.ofNullable(options.getTimestamp()))
                        .build()
                    )
                    .put("search", ImmutableMap.builder()
                        .put("current", Optional.ofNullable(options.getSearch()))
                        .build()
                    )
                    .build()
                )
                .put("pagination", ImmutableMap.builder()
                    .put("size", options.getPartition() == null ? topic.getSize() : topic.getSize(options.getPartition()))
                    .put("before", options.before(data, uri).toNormalizedURI(false).toString())
                    .put("after", options.after(data, uri).toNormalizedURI(false).toString())
                    .build()
                )
        );
    }

    @GET
    @Path("{topic}/{tab:(partitions|groups|configs|logs)}")
    public View tab(Request request) throws ExecutionException, InterruptedException {
        return this.topic(request, request.param("tab").value());
    }

    public View topic(Request request, String tab) throws ExecutionException, InterruptedException {
        Topic topic = this.topicRepository.findByName(request.param("topic").value());
        List<Config> configs = this.configRepository.findByTopic(request.param("topic").value());

        return this.template(
            request,
            Results
                .html("topic")
                .put("tab", tab)
                .put("topic", topic)
                .put("configs", configs)
        );
    }

    @GET
    @Path("{topic}/deleteRecord")
    public Result deleteRecord(Request request) {
        String topic = request.param("topic").value();
        Integer partition = request.param("partition").intValue();
        String key = request.param("key").value();

        ResultStatusResponse result = new ResultStatusResponse();

        try {
            this.recordRepository.delete(
                request.param("cluster").value(),
                topic,
                partition,
                key
            );

            result.result = true;
            result.message = "Record will be deleted on compaction";

            return Results.with(result, 200);
        } catch (Exception exception) {
            logger.error("Failed to delete record " + key, exception);

            result.result = false;
            result.message = exception.getCause().getMessage();

            return Results.with(result, 500);
        }
    }

    @GET
    @Path("{topic}/delete")
    public Result delete(Request request) {
        String name = request.param("topic").value();
        ResultStatusResponse result = new ResultStatusResponse();

        try {
            this.topicRepository.delete(request.param("cluster").value(), name);

            result.result = true;
            result.message = "Topic '" + name + "' is deleted";

            return Results.with(result, 200);
        } catch (Exception exception) {
            logger.error("Failed to delete topic " + name, exception);

            result.result = false;
            result.message = exception.getCause().getMessage();

            return Results.with(result, 500);
        }
    }
}
