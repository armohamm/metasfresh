package de.metas.ordercandidate.api;

import lombok.Builder;
import lombok.Value;

import javax.annotation.Nullable;

import de.metas.bpartner.BPartnerId;

/*
 * #%L
 * de.metas.swat.base
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

@Value
public final class OLCandBPartnerInfo
{
	private final BPartnerId bpartnerId;
	private final int bpartnerLocationId;
	private final int contactId;

	@Builder
	private OLCandBPartnerInfo(
			@Nullable final BPartnerId bpartnerId,
			final int bpartnerLocationId,
			final int contactId)
	{
		this.bpartnerId = bpartnerId;
		this.bpartnerLocationId = bpartnerLocationId > 0 ? bpartnerLocationId : -1;
		this.contactId = contactId > 0 ? contactId : -1;
	}
}
