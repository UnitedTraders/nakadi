package org.zalando.nakadi.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.NativeWebRequest;
import org.zalando.nakadi.domain.EventType;
import org.zalando.nakadi.domain.NakadiCursor;
import org.zalando.nakadi.domain.NakadiCursorLag;
import org.zalando.nakadi.domain.ShiftedNakadiCursor;
import org.zalando.nakadi.exceptions.InternalNakadiException;
import org.zalando.nakadi.exceptions.InvalidCursorException;
import org.zalando.nakadi.exceptions.NakadiException;
import org.zalando.nakadi.exceptions.NoSuchEventTypeException;
import org.zalando.nakadi.exceptions.NotFoundException;
import org.zalando.nakadi.exceptions.ServiceUnavailableException;
import org.zalando.nakadi.exceptions.runtime.CursorConversionException;
import org.zalando.nakadi.exceptions.runtime.InvalidCursorOperation;
import org.zalando.nakadi.exceptions.runtime.MyNakadiRuntimeException1;
import org.zalando.nakadi.exceptions.runtime.NoEventTypeException;
import org.zalando.nakadi.repository.EventTypeRepository;
import org.zalando.nakadi.security.Client;
import org.zalando.nakadi.service.CursorConverter;
import org.zalando.nakadi.service.CursorOperationsService;
import org.zalando.nakadi.util.ValidListWrapper;
import org.zalando.nakadi.view.Cursor;
import org.zalando.nakadi.view.CursorDistance;
import org.zalando.nakadi.view.CursorLag;
import org.zalando.nakadi.view.ShiftedCursor;
import org.zalando.problem.MoreStatus;
import org.zalando.problem.Problem;
import org.zalando.problem.spring.web.advice.Responses;

import javax.validation.Valid;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.springframework.http.HttpStatus.OK;
import static org.springframework.http.ResponseEntity.status;

@RestController
public class CursorOperationsController {

    private static final Logger LOG = LoggerFactory.getLogger(CursorOperationsController.class);

    private final CursorConverter cursorConverter;
    private final CursorOperationsService cursorOperationsService;
    private final EventTypeRepository eventTypeRepository;

    @Autowired
    public CursorOperationsController(final CursorOperationsService cursorOperationsService,
                             final CursorConverter cursorConverter, final EventTypeRepository eventTypeRepository) {
        this.cursorOperationsService = cursorOperationsService;
        this.cursorConverter = cursorConverter;
        this.eventTypeRepository = eventTypeRepository;
    }

    @RequestMapping(path = "/event-types/{eventTypeName}/cursor-distances", method = RequestMethod.POST)
    public ResponseEntity<?> getDistance(@PathVariable("eventTypeName") final String eventTypeName,
                                         @Valid @RequestBody final ValidListWrapper<CursorDistance> queries,
                                         final Client client) {
        checkReadScopes(eventTypeName, client);

        queries.getList().forEach(query -> {
            try {
                final NakadiCursor initialCursor = cursorConverter
                        .convert(eventTypeName, query.getInitialCursor());
                final NakadiCursor finalCursor = cursorConverter
                        .convert(eventTypeName, query.getFinalCursor());
                final Long distance = cursorOperationsService.calculateDistance(initialCursor, finalCursor);
                query.setDistance(distance);
            } catch (InternalNakadiException | ServiceUnavailableException e) {
                throw new MyNakadiRuntimeException1("problem calculating cursors distance", e);
            } catch (final NoSuchEventTypeException e) {
                throw new NotFoundException("event type not found", e);
            } catch (final InvalidCursorException e) {
                throw new CursorConversionException("problem converting cursors", e);
            }
        });

        return status(OK).body(queries.getList());
    }

    @RequestMapping(path = "/event-types/{eventTypeName}/shifted-cursors", method = RequestMethod.POST)
    public ResponseEntity<?> moveCursors(@PathVariable("eventTypeName") final String eventTypeName,
                                         @Valid @RequestBody final ValidListWrapper<ShiftedCursor> cursors,
                                         final Client client) {
        checkReadScopes(eventTypeName, client);

        final List<ShiftedNakadiCursor> domainCursor = cursors.getList().stream()
                .map(this.toShiftedNakadiCursor(eventTypeName))
                .collect(Collectors.toList());

        final List<NakadiCursor> domainResultCursors = cursorOperationsService.unshiftCursors(domainCursor);

        final List<Cursor> viewResult = domainResultCursors.stream().map(cursorConverter::convert)
                .collect(Collectors.toList());

        return status(OK).body(viewResult);
    }

    @RequestMapping(path = "/event-types/{eventTypeName}/cursors-lag", method = RequestMethod.POST)
    public ResponseEntity<?> cursorsLag(@PathVariable("eventTypeName") final String eventTypeName,
                                        @Valid @RequestBody final ValidListWrapper<Cursor> cursors,
                                        final Client client) {
        checkReadScopes(eventTypeName, client);

        final List<NakadiCursor> domainCursor = cursors.getList().stream()
                .map(toNakadiCursor(eventTypeName))
                .collect(Collectors.toList());

        final List<NakadiCursorLag> lagResult = cursorOperationsService
                .cursorsLag(eventTypeName, domainCursor);

        final List<CursorLag> viewResult = lagResult.stream().map(this::toCursorLag)
                .collect(Collectors.toList());

        return status(OK).body(viewResult);
    }

    @ExceptionHandler(InvalidCursorOperation.class)
    public ResponseEntity<?> invalidCursorOperation(final InvalidCursorOperation e,
                                                          final NativeWebRequest request) {
        LOG.debug("User provided invalid cursor for operation. Reason: " + e.getReason(), e);
        return Responses.create(Problem.valueOf(MoreStatus.UNPROCESSABLE_ENTITY,
                clientErrorMessage(e.getReason())), request);
    }

    private String clientErrorMessage(final InvalidCursorOperation.Reason reason) {
        switch (reason) {
            case INVERTED_TIMELINE_ORDER: return "Inverted timelines. Final cursor must correspond to a newer " +
                    "timeline than initial cursor.";

            case TIMELINE_NOT_FOUND: return "Timeline not found. It might happen in case the cursor refers to a " +
                    "timeline that has already expired.";
            case INVERTED_OFFSET_ORDER: return "Inverted offsets. Final cursor offsets must be newer than initial " +
                    "cursor offsets";
            case PARTITION_NOT_FOUND: return "Partition not found.";
            case CURSORS_WITH_DIFFERENT_PARTITION: return "Cursors with different partition. Pairs of cursors should " +
                    "have matching partitions.";
            default:
                LOG.error("Unexpected invalid cursor operation reason " + reason);
                throw new MyNakadiRuntimeException1();
        }
    }

    private void checkReadScopes(final String eventTypeName, final Client client) {
        final EventType eventType;
        try {
            eventType = eventTypeRepository.findByName(eventTypeName);
            client.checkScopes(eventType.getReadScopes());
        } catch (final InternalNakadiException e) {
            throw new MyNakadiRuntimeException1("failed to get event type", e);
        } catch (final NoSuchEventTypeException e) {
            throw new NoEventTypeException(e.getMessage(), e);
        }
    }

    private CursorLag toCursorLag(final NakadiCursorLag nakadiCursorLag) {
        return new CursorLag(
                nakadiCursorLag.getPartition(),
                cursorConverter.convert(nakadiCursorLag.getFirstCursor()).getOffset(),
                cursorConverter.convert(nakadiCursorLag.getLastCursor()).getOffset(),
                nakadiCursorLag.getLag()
        );
    }

    private Function<Cursor, NakadiCursor> toNakadiCursor(final String eventTypeName) {
        return cursor -> {
            try {
                return cursorConverter.convert(eventTypeName, cursor);
            } catch (final NakadiException | InvalidCursorException e) {
                throw new CursorConversionException("problem converting cursors", e);
            }
        };
    }

    private Function<ShiftedCursor, ShiftedNakadiCursor> toShiftedNakadiCursor(final String eventTypeName) {
        return (cursor) -> {
            try {
                final NakadiCursor nakadiCursor = cursorConverter.convert(eventTypeName, cursor);
                return new ShiftedNakadiCursor(nakadiCursor.getTimeline(), nakadiCursor.getPartition(),
                        nakadiCursor.getOffset(), cursor.getShift());
            } catch (final NakadiException | InvalidCursorException e) {
                throw new CursorConversionException("problem converting cursors", e);
            }
        };
    }
}
