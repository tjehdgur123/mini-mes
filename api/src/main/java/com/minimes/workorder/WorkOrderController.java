package com.minimes.workorder;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.minimes.workorder.WorkOrderService.CreateRequest;
import com.minimes.workorder.WorkOrderService.CreateResponse;
import com.minimes.workorder.WorkOrderService.WorkOrderRow;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/work-orders")
public class WorkOrderController {

    private final WorkOrderService service;

    public WorkOrderController(WorkOrderService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CreateResponse create(@Valid @RequestBody CreateRequest req) {
        return service.create(req);
    }

    @GetMapping
    public List<WorkOrderRow> list() {
        return service.list();
    }
}
