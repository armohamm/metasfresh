package de.metas.attachments.process;

import org.compiere.Adempiere;

import de.metas.attachments.AttachmentEntry;
import de.metas.attachments.AttachmentEntryId;
import de.metas.attachments.AttachmentEntryService;
import de.metas.process.JavaProcess;

/*
 * #%L
 * de.metas.adempiere.adempiere.base
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

public class AD_AttachmentEntry_Download extends JavaProcess
{
	private final transient AttachmentEntryService attachmentEntryService = Adempiere.getBean(AttachmentEntryService.class);

	@Override
	protected String doIt()
	{
		final AttachmentEntryId entryId = AttachmentEntryId.ofRepoId(getRecord_ID());

		final AttachmentEntry entry = attachmentEntryService.getById(entryId);
		final byte[] data = attachmentEntryService.retrieveData(entry.getId());

		getResult().setReportData(data, entry.getFilename(), entry.getMimeType());

		return MSG_OK;
	}

}
