package de.metas.material.planning.pporder;

import static org.compiere.util.TimeUtil.asDate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import org.adempiere.mm.attributes.api.AttributesKeys;
import org.compiere.model.I_M_Product;
import org.compiere.util.TimeUtil;
import org.eevolution.api.BOMComponentType;
import org.eevolution.api.IProductBOMBL;
import org.eevolution.api.IProductBOMDAO;
import org.eevolution.model.I_PP_Product_BOM;
import org.eevolution.model.I_PP_Product_BOMLine;
import org.eevolution.model.I_PP_Product_Planning;
import org.slf4j.Logger;
import org.springframework.stereotype.Service;

import de.metas.logging.LogManager;
import de.metas.material.event.ModelProductDescriptorExtractor;
import de.metas.material.event.commons.AttributesKey;
import de.metas.material.event.commons.ProductDescriptor;
import de.metas.material.event.pporder.PPOrder;
import de.metas.material.event.pporder.PPOrder.PPOrderBuilder;
import de.metas.material.event.pporder.PPOrderLine;
import de.metas.material.planning.IMaterialPlanningContext;
import de.metas.material.planning.IMaterialRequest;
import de.metas.material.planning.IProductPlanningDAO;
import de.metas.material.planning.ProductPlanningBL;
import de.metas.material.planning.RoutingService;
import de.metas.material.planning.RoutingServiceFactory;
import de.metas.material.planning.exception.MrpException;
import de.metas.product.ProductId;
import de.metas.product.ResourceId;
import de.metas.quantity.Quantity;
import de.metas.uom.IUOMConversionBL;
import de.metas.util.Services;
import lombok.NonNull;

/*
 * #%L
 * metasfresh-material-planning
 * %%
 * Copyright (C) 2017 metas GmbH
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 2 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public
 * License along with this program. If not, see
 * <http://www.gnu.org/licenses/gpl-2.0.html>.
 * #L%
 */
@Service
public class PPOrderPojoSupplier
{
	private static final transient Logger logger = LogManager.getLogger(PPOrderPojoSupplier.class);

	private final ProductPlanningBL productPlanningBL;

	private final ModelProductDescriptorExtractor productDescriptorFactory;

	public PPOrderPojoSupplier(
			@NonNull final ProductPlanningBL productPlanningBL,
			@NonNull final ModelProductDescriptorExtractor productDescriptorFactory)
	{
		this.productPlanningBL = productPlanningBL;
		this.productDescriptorFactory = productDescriptorFactory;
	}

	public PPOrder supplyPPOrderPojoWithLines(
			@NonNull final IMaterialRequest request)
	{
		final PPOrder ppOrder = supplyPPOrderPojo(request);

		final PPOrder ppOrderWithLines = ppOrder
				.toBuilder()
				.lines(supplyPPOrderLinePojos(ppOrder)).build();

		return ppOrderWithLines;
	}

	private PPOrder supplyPPOrderPojo(@NonNull final IMaterialRequest request)
	{
		final IMaterialPlanningContext mrpContext = request.getMrpContext();

		mrpContext.assertContextConsistent();

		final I_PP_Product_Planning productPlanningData = mrpContext.getProductPlanning();
		final I_M_Product product = mrpContext.getM_Product();

		final Instant demandDateStartSchedule = request.getDemandDate();
		final Quantity qtyToSupply = request.getQtyToSupply();

		// BOM
		if (productPlanningData.getPP_Product_BOM_ID() <= 0)
		{
			throw new MrpException("@FillMandatory@ @PP_Product_BOM_ID@ ( @M_Product_ID@=" + product.getValue() + ")");
		}

		//
		// Routing (Workflow)
		final int adWorkflowId = productPlanningData.getAD_Workflow_ID();
		if (adWorkflowId <= 0)
		{
			throw new MrpException("@FillMandatory@ @AD_Workflow_ID@ ( @M_Product_ID@=" + product.getValue() + ")");
		}

		//
		// Calculate duration & Planning dates
		final int durationDays = calculateDurationDays(mrpContext, qtyToSupply.getAsBigDecimal());
		final Instant dateFinishSchedule = demandDateStartSchedule;

		final Instant dateStartSchedule = dateFinishSchedule.minus(durationDays, ChronoUnit.DAYS);

		final ProductDescriptor productDescriptor = createPPOrderProductDescriptor(mrpContext);

		final ProductId productId = ProductId.ofRepoId(mrpContext.getM_Product_ID());
		final Quantity ppOrderQuantity = Services.get(IUOMConversionBL.class).convertToProductUOM(qtyToSupply, productId);

		final PPOrderBuilder ppOrderPojoBuilder = PPOrder.builder()
				.orgId(mrpContext.getAD_Org_ID())

				// Planning dimension
				.plantId(mrpContext.getPlant_ID())
				.warehouseId(mrpContext.getM_Warehouse_ID())
				.productPlanningId(productPlanningData.getPP_Product_Planning_ID())

				// Product, UOM, ASI
				.productDescriptor(productDescriptor)

				// Dates
				.datePromised(dateFinishSchedule)
				.dateStartSchedule(dateStartSchedule)

				.qtyRequired(ppOrderQuantity.getAsBigDecimal())

				.orderLineId(request.getMrpDemandOrderLineSOId())
				.bPartnerId(request.getMrpDemandBPartnerId());

		return ppOrderPojoBuilder.build();
	}

	/**
	 * Creates the "header" product descriptor.
	 * Does not use the given {@code mrpContext}'s product-planning record,
	 * because it might have less specific (or none!) storageAttributesKey.
	 */
	private ProductDescriptor createPPOrderProductDescriptor(final IMaterialPlanningContext mrpContext)
	{
		final int asiId = mrpContext.getM_AttributeSetInstance_ID();
		final AttributesKey attributesKey = AttributesKeys
				.createAttributesKeyFromASIStorageAttributes(asiId)
				.orElse(AttributesKey.NONE);

		final ProductDescriptor productDescriptor = ProductDescriptor.forProductAndAttributes(
				mrpContext.getM_Product_ID(),
				attributesKey,
				asiId);
		return productDescriptor;
	}

	private int calculateDurationDays(
			@NonNull final IMaterialPlanningContext mrpContext,
			@NonNull final BigDecimal qty)
	{
		final int leadtimeDays = calculateLeadtimeDays(mrpContext, qty);
		final int durationTotalDays = productPlanningBL.calculateDurationDays(leadtimeDays, mrpContext.getProductPlanning());
		return durationTotalDays;
	}

	private int calculateLeadtimeDays(
			@NonNull final IMaterialPlanningContext mrpContext,
			@NonNull final BigDecimal qty)
	{
		final I_PP_Product_Planning productPlanningData = mrpContext.getProductPlanning();

		final int leadtimeDays = productPlanningData.getDeliveryTime_Promised().intValueExact();
		if (leadtimeDays > 0)
		{
			// Leadtime was set in Product Planning/ take the leadtime as it is
			return leadtimeDays;
		}

		final PPRoutingId routingId = PPRoutingId.ofRepoId(productPlanningData.getAD_Workflow_ID());
		final ResourceId plantId = ResourceId.ofRepoIdOrNull(productPlanningData.getS_Resource_ID());
		final RoutingService routingService = RoutingServiceFactory.get().getRoutingService();
		final int leadtimeCalc = routingService.calculateDurationDays(routingId, plantId, qty);
		return leadtimeCalc;
	}

	private List<PPOrderLine> supplyPPOrderLinePojos(@NonNull final PPOrder ppOrder)
	{
		final I_PP_Product_BOM productBOM = retriveAndVerifyBOM(ppOrder);

		final IProductBOMBL productBOMBL = Services.get(IProductBOMBL.class);

		final List<PPOrderLine> result = new ArrayList<>();

		final List<I_PP_Product_BOMLine> productBOMLines = Services.get(IProductBOMDAO.class).retrieveLines(productBOM);
		for (final I_PP_Product_BOMLine productBomLine : productBOMLines)
		{
			if (!productBOMBL.isValidFromTo(productBomLine, TimeUtil.asDate(ppOrder.getDateStartSchedule())))
			{
				logger.debug("BOM Line skipped - " + productBomLine);
				continue;
			}

			final ProductDescriptor productDescriptor = productDescriptorFactory.createProductDescriptor(productBomLine);

			final boolean receipt = PPOrderUtil.isReceipt(BOMComponentType.ofCode(productBomLine.getComponentType()));

			final PPOrderLine intermedidatePPOrderLine = PPOrderLine.builder()
					.productBomLineId(productBomLine.getPP_Product_BOMLine_ID())
					.description(productBomLine.getDescription())
					.productDescriptor(productDescriptor)
					.receipt(receipt)
					.issueOrReceiveDate(receipt ? ppOrder.getDatePromised() : ppOrder.getDateStartSchedule())
					.qtyRequired(BigDecimal.ZERO) // is computed in the next step
					.build();

			final IPPOrderBOMBL ppOrderBOMBL = Services.get(IPPOrderBOMBL.class);
			final Quantity qtyRequired = ppOrderBOMBL.calculateQtyRequired(intermedidatePPOrderLine, ppOrder.getQtyRequired());

			final PPOrderLine ppOrderLine = intermedidatePPOrderLine.withQtyRequired(qtyRequired.getAsBigDecimal());

			result.add(ppOrderLine);
		}

		return result;
	}

	private I_PP_Product_BOM retriveAndVerifyBOM(@NonNull final PPOrder ppOrder)
	{
		final Instant dateStartSchedule = ppOrder.getDateStartSchedule();
		final ProductId ppOrderProductId = ProductId.ofRepoId(ppOrder.getProductDescriptor().getProductId());

		final IProductPlanningDAO productPlanningsRepo = Services.get(IProductPlanningDAO.class);
		final I_PP_Product_Planning productPlanning = productPlanningsRepo.getById(ppOrder.getProductPlanningId());

		final IProductBOMDAO productBOMsRepo = Services.get(IProductBOMDAO.class);
		final I_PP_Product_BOM productBOM = productBOMsRepo.getById(productPlanning.getPP_Product_BOM_ID());

		return PPOrderUtil.verifyProductBOMAndReturnIt(ppOrderProductId, asDate(dateStartSchedule), productBOM);
	}
}
