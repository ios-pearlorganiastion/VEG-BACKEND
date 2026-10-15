package com.veggofresh.complaint.dto;

import com.veggofresh.complaint.entity.ComplaintCategory;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

/**
 * Request body for registering a new complaint.
 * Note: userId and userRole are NOT passed in this DTO;
 * they are strictly extracted from the caller's JWT token.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateComplaintRequestDto {

    @NotNull(message = "Category is required")
    private ComplaintCategory category;

    @NotBlank(message = "Title is required")
    @Size(max = 255, message = "Title must not exceed 255 characters")
    private String title;

    @NotBlank(message = "Description is required")
    private String description;

    /**
     * Optional order ID reference if the complaint concerns an order.
     */
    private UUID orderId;

    /**
     * Optional image / proof URLs.
     */
    private List<String> attachments;
}
